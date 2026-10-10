package com.nexusphere.ledger.reporting.application;

import com.nexusphere.ledger.chain.CanonicalJson;
import com.nexusphere.ledger.compliance.application.Compliance;
import com.nexusphere.ledger.compliance.domain.model.ComplianceProfile;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.server.web.LedgerException;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.ReportVerifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportService {

    public static final String NOTICE = "This report maps recorded evidence to requirements. It is not legal advice"
            + " and not a certification of compliance; the evidence package it names is the record.";

    public record Offered(String id, String title, String description, List<String> profiles) {
    }

    public record Subject(String title, String description) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("title", title);
            map.put("description", description);
            return map;
        }
    }

    private final Compliance compliance;
    private final LedgerSigner signer;
    private final Clock clock;

    ReportService(Compliance compliance, LedgerSigner signer, Clock clock) {
        this.compliance = compliance;
        this.signer = signer;
        this.clock = clock;
        for (ComplianceProfile profile : compliance.profiles()) {
            for (String report : profile.reports()) {
                if (ReportTemplates.find(report).isEmpty()) {
                    throw new IllegalStateException("The compliance profile " + profile.id() + " names the report "
                            + report + ", which has no template; the ledger has " + ReportTemplates.ids());
                }
            }
        }
    }

    public List<Offered> offered() {
        return compliance.reports().stream().map(id -> {
            ReportTemplates.Template template = ReportTemplates.find(id).orElseThrow();
            return new Offered(id, template.title(), template.description(), profiles(id).stream()
                    .map(ComplianceProfile::id).toList());
        }).toList();
    }

    public ReportTemplates.Template template(String id) {
        if (!compliance.reports().contains(id)) {
            throw LedgerException.notFound("Report " + id + " in the active compliance profiles");
        }
        return ReportTemplates.find(id).orElseThrow(() -> LedgerException.notFound("Report template " + id));
    }

    public Map<String, Object> create(String id, JsonNode pkg, PackageReport verification, Subject subject) {
        ReportTemplates.Template template = template(id);
        List<ReportSection> sections = template.sections().apply(ReportFacts.of(pkg, verification, compliance));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("format", ReportVerifier.FORMAT);
        report.put("report", id);
        report.put("title", template.title());
        report.put("createdAt", clock.instant().truncatedTo(ChronoUnit.MICROS).toString());
        report.put("profiles", profiles(id).stream().map(profile -> {
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("id", profile.id());
            reference.put("digest", profile.digest());
            return reference;
        }).toList());
        report.put("subject", subject == null ? null : subject.toMap());
        JsonNode scope = pkg.path("scope");
        Map<String, Object> scopeMap = new LinkedHashMap<>();
        scopeMap.put("agentId", scope.path("agentId").asString(null));
        scopeMap.put("principalId", scope.path("principalId").asString(null));
        scopeMap.put("fromSequence", scope.path("fromSequence").asLong());
        scopeMap.put("toSequence", scope.path("toSequence").asLong());
        report.put("scope", scopeMap);
        List<String> hashes = new ArrayList<>();
        pkg.path("links").forEach(link -> {
            if (link.path("entry").isObject()) {
                hashes.add(link.path("hash").asString());
            }
        });
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("checkpoint", pkg.path("checkpoint").path("sequence").asLong());
        evidence.put("headHash", pkg.path("checkpoint").path("headHash").asString());
        evidence.put("entries", (long) hashes.size());
        evidence.put("entriesDigest", ReportVerifier.entriesDigest(hashes));
        evidence.put("valid", verification.valid());
        report.put("evidence", evidence);
        report.put("notice", NOTICE);
        report.put("sections", sections.stream().map(ReportSection::toMap).toList());
        report.put("keyId", signer.publicKey().keyId());
        report.put("signature", signer.signBytes(CanonicalJson.bytes(report)));
        return report;
    }

    private List<ComplianceProfile> profiles(String id) {
        return compliance.profiles().stream().filter(profile -> profile.reports().contains(id)).toList();
    }
}
