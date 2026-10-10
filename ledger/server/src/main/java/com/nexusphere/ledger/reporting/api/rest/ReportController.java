package com.nexusphere.ledger.reporting.api.rest;

import com.nexusphere.ledger.compliance.application.Compliance;
import com.nexusphere.ledger.evidence.api.rest.PackageDocuments;
import com.nexusphere.ledger.evidence.domain.model.PackageRequest;
import com.nexusphere.ledger.reporting.application.ReportHtml;
import com.nexusphere.ledger.reporting.application.ReportService;
import com.nexusphere.ledger.reporting.application.ReportTemplates;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.LedgerException;
import com.nexusphere.ledger.transparency.application.RekorService;
import com.nexusphere.ledger.transparency.application.TimestampService;
import com.nexusphere.ledger.transparency.application.WitnessCollector;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.verifier.PackageReport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
class ReportController {

    record ReportRequest(String agentId, String principalId, Long fromSequence, Long toSequence) {
    }

    record DisputeRequest(String agentId, String principalId, Long fromSequence, Long toSequence, String title,
                          String description) {
    }

    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final ReportService reports;
    private final PackageDocuments documents;
    private final Compliance compliance;
    private final TimestampService timestamps;
    private final RekorService rekor;
    private final WitnessCollector witnesses;
    private final JsonMapper json;

    ReportController(ReportService reports, PackageDocuments documents, Compliance compliance,
                     TimestampService timestamps, RekorService rekor, WitnessCollector witnesses, JsonMapper json) {
        this.reports = reports;
        this.documents = documents;
        this.compliance = compliance;
        this.timestamps = timestamps;
        this.rekor = rekor;
        this.witnesses = witnesses;
        this.json = json;
    }

    @GetMapping("/api/v1/reports")
    List<ReportService.Offered> offered(Caller caller) {
        caller.requireOperator();
        return reports.offered();
    }

    @PostMapping("/api/v1/reports/{id}")
    ResponseEntity<?> create(Caller caller, @PathVariable String id, @RequestParam(required = false) String format,
                             @RequestBody(required = false) ReportRequest request) {
        caller.requireOperator();
        reports.template(id);
        ReportRequest r = request == null ? new ReportRequest(null, null, null, null) : request;
        JsonNode pkg = documents.tree(documents.export(new PackageRequest(r.agentId(), r.principalId(),
                r.fromSequence(), r.toSequence())));
        Map<String, Object> report = reports.create(id, pkg, documents.verifyWithAnchors(pkg), null);
        if ("html".equals(format)) {
            return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(ReportHtml.render(report));
        }
        if (format != null && !"json".equals(format)) {
            throw LedgerException.invalid("format must be json or html", Map.of("format", format));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(report);
    }

    @PostMapping("/api/v1/disputes")
    ResponseEntity<byte[]> dispute(Caller caller, @RequestBody DisputeRequest request) {
        caller.requireOperator();
        Map<String, Object> errors = new LinkedHashMap<>();
        if (request.title() == null || request.title().isBlank() || request.title().length() > 200) {
            errors.put("title", "is required, at most 200 characters");
        }
        if (request.description() != null && request.description().length() > 4000) {
            errors.put("description", "must be at most 4000 characters");
        }
        if (!errors.isEmpty()) {
            throw LedgerException.invalid("The dispute request has invalid fields.", errors);
        }
        reports.template(ReportTemplates.DISPUTE);
        JsonNode pkg = documents.tree(documents.export(new PackageRequest(request.agentId(), request.principalId(),
                request.fromSequence(), request.toSequence())));
        PackageReport verification = documents.verifyWithAnchors(pkg);
        Map<String, Object> report = reports.create(ReportTemplates.DISPUTE, pkg, verification,
                new ReportService.Subject(request.title().trim(), request.description()));
        long sequence = pkg.path("checkpoint").path("sequence").asLong();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.txt", readme(request.title().trim(), report, sequence).getBytes(StandardCharsets.UTF_8));
        files.put("package.json", PRETTY.writeValueAsBytes(pkg));
        files.put("report.json", PRETTY.writeValueAsBytes(report));
        files.put("report.html", ReportHtml.render(report).getBytes(StandardCharsets.UTF_8));
        files.put("keys.json", PRETTY.writeValueAsBytes(json.valueToTree(documents.keys())));
        files.put("compliance.json", PRETTY.writeValueAsBytes(compliance()));
        if (!timestamps.certificates().isEmpty()) {
            files.put("timestamp-authority.pem", String.join("", timestamps.authority().certificates())
                    .getBytes(StandardCharsets.UTF_8));
        }
        if (rekor.enabled()) {
            files.put("rekor.json", PRETTY.writeValueAsBytes(rekor.log()));
        }
        if (!witnesses.witnessKeys().isEmpty()) {
            files.put("witnesses.txt", (String.join("\n", witnesses.witnessKeys().stream().map(NoteKey::vkey)
                    .toList()) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok().contentType(ZIP)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dispute-" + sequence + ".zip\"")
                .body(zip(files));
    }

    private Map<String, Object> compliance() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("region", compliance.region());
        map.put("profiles", compliance.profiles().stream().map(profile -> {
            Map<String, Object> definition = new LinkedHashMap<>(profile.definition());
            definition.put("digest", profile.digest());
            return definition;
        }).toList());
        map.put("effective", compliance.effective());
        return map;
    }

    private static String readme(String title, Map<String, Object> report, long sequence) {
        return """
                Nexusphere Ledger dispute package

                %s

                Created %s from checkpoint %d of the ledger.

                Files
                  package.json             The evidence package: entries, hash chain, checkpoints, log proofs, receipts
                  report.json              The dispute report, signed by the ledger
                  report.html              The same report for reading and printing
                  keys.json                The ledger's signing keys with their rotations and revocations
                  compliance.json          The compliance profiles the ledger ran with
                  timestamp-authority.pem  The certificate chain of the timestamp authority, when the ledger uses one
                  rekor.json               The Rekor log the ledger anchors checkpoints in, when it uses one
                  witnesses.txt            The verifier keys of the witnesses that cosign the log, when there are any

                Checking it
                  nexusphere-ledger-verify dispute --public-key <the ledger's public key> dispute.zip

                The keys, timestamp authority, Rekor log and witnesses in this archive come from the ledger itself.
                Compare them with copies obtained on your own before you rely on the result.

                %s
                """.formatted(title, report.get("createdAt"), sequence, ReportService.NOTICE);
    }

    private static byte[] zip(Map<String, byte[]> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
