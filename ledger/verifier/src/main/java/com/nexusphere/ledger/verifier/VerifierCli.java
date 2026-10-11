package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.TimestampToken;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class VerifierCli {

    public static final int VALID = 0;
    public static final int INVALID = 1;
    public static final int USAGE = 2;

    private static final String USAGE_TEXT = """
            Usage: nexusphere-ledger-verify [--public-key <base64 X.509 Ed25519 key> | --public-key-file <file>] \
            [--keys <keys.json>] [--witness <verifier key>]... [--witnesses-required <n>] \
            [--timestamp-certificates <chain.pem>] [--require-timestamp] [--rekor-key <verifier key>]... \
            [--require-rekor] [--json] <package.json>
                   nexusphere-ledger-verify mandate --issuer <url> [--issuer <url>] [--audience <aud>] \
            [--action <action> --target <target>] [--public-key <key> | --public-key-file <file>] [--skip-status] \
            [--nonce <nonce>] [--require-key-binding] [--json] <token | token file | ->
                   nexusphere-ledger-verify statement [--public-key <key> | --public-key-file <file>] \
            [--receipt <receipt.cose>] <statement.cose>
                   nexusphere-ledger-verify report [package options] --package <package.json> <report.json>
                   nexusphere-ledger-verify dispute [package options] <dispute.zip>
                   nexusphere-ledger-verify index [--public-key <key> | --public-key-file <file>] [--keys <keys.json>] \
            [--package <package.json>] [--json] <proof.json>
                   nexusphere-ledger-verify track-record --issuer <url> [--issuer <url>] [--audience <aud>] \
            [--public-key <key> | --public-key-file <file>] [--nonce <nonce>] [--require-key-binding] \
            [--index-proof <proof.json>] [--json] <token | token file | ->
                   nexusphere-ledger-verify reconcile [--public-key <key> | --public-key-file <file>] \
            [--peer-public-key <key> | --peer-public-key-file <file>] [--json] <ours.jwt> <theirs.jwt>
                   nexusphere-ledger-verify archive [--json] <log url> <directory>
                   nexusphere-ledger-verify tiles [--public-key <key> | --public-key-file <file>] \
            [--package <package.json>] [--json] <directory>""";

    private static final int MAX_FILE = 256 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private VerifierCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0 && "mandate".equals(args[0])) {
            return MandateCli.run(Arrays.copyOfRange(args, 1, args.length), out, err, USAGE_TEXT);
        }
        if (args.length > 0 && "track-record".equals(args[0])) {
            return TrackRecordCli.run(Arrays.copyOfRange(args, 1, args.length), out, err, USAGE_TEXT);
        }
        if (args.length > 0 && "reconcile".equals(args[0])) {
            return ReconcileCli.run(Arrays.copyOfRange(args, 1, args.length), out, err, USAGE_TEXT);
        }
        if (args.length > 0 && "statement".equals(args[0])) {
            return StatementCli.run(Arrays.copyOfRange(args, 1, args.length), out, err, USAGE_TEXT);
        }
        if (args.length > 0 && "archive".equals(args[0])) {
            return TileCli.archive(Arrays.copyOfRange(args, 1, args.length), out, err, USAGE_TEXT);
        }
        if (args.length > 0 && "tiles".equals(args[0])) {
            return TileCli.verify(Arrays.copyOfRange(args, 1, args.length), out, err, USAGE_TEXT);
        }
        if (args.length > 0 && "report".equals(args[0])) {
            return report(Arrays.copyOfRange(args, 1, args.length), out, err);
        }
        if (args.length > 0 && "index".equals(args[0])) {
            return index(Arrays.copyOfRange(args, 1, args.length), out, err);
        }
        if (args.length > 0 && "dispute".equals(args[0])) {
            return dispute(Arrays.copyOfRange(args, 1, args.length), out, err);
        }
        Options options = Options.parse(args, false);
        if (options == null) {
            err.println(USAGE_TEXT);
            return USAGE;
        }
        JsonNode pkg;
        JsonNode keys;
        List<X509Certificate> certificates;
        Path reading = options.file();
        try {
            pkg = JSON.readTree(Files.readAllBytes(options.file()));
            reading = options.keysFile();
            keys = options.keysFile() == null ? null : JSON.readTree(Files.readAllBytes(options.keysFile()));
            reading = options.timestampCertificates();
            certificates = options.timestampCertificates() == null ? List.of()
                    : TimestampToken.readCertificates(Files.readAllBytes(options.timestampCertificates()));
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            err.println("Cannot read " + reading + ": " + e.getMessage());
            return USAGE;
        }
        PackageReport report = verifyPackage(pkg, keys, certificates, options.rekorKeys(), options);
        if (options.json()) {
            out.println(JSON.writeValueAsString(report));
        } else {
            print(report, !certificates.isEmpty(), !options.rekorKeys().isEmpty(), out);
        }
        return report.valid() ? VALID : INVALID;
    }

    private record Options(String publicKey, List<NoteKey> witnesses, Integer required, Path keysFile,
                           Path timestampCertificates, boolean requireTimestamp, List<NoteKey> rekorKeys,
                           boolean requireRekor, boolean json, Path packageFile, Path file) {

        static Options parse(String[] args, boolean withPackage) {
            String publicKey = null;
            List<NoteKey> witnesses = new ArrayList<>();
            Integer required = null;
            Path keysFile = null;
            Path timestampCertificates = null;
            boolean requireTimestamp = false;
            List<NoteKey> rekorKeys = new ArrayList<>();
            boolean requireRekor = false;
            boolean json = false;
            Path packageFile = null;
            Path file = null;
            try {
                for (int i = 0; i < args.length; i++) {
                    switch (args[i]) {
                        case "--public-key" -> publicKey = args[++i].trim();
                        case "--public-key-file" -> publicKey = Files.readString(Path.of(args[++i])).trim();
                        case "--keys" -> keysFile = Path.of(args[++i]);
                        case "--witness" -> witnesses.add(NoteKey.parse(args[++i]));
                        case "--witnesses-required" -> required = Integer.parseInt(args[++i]);
                        case "--timestamp-certificates" -> timestampCertificates = Path.of(args[++i]);
                        case "--require-timestamp" -> requireTimestamp = true;
                        case "--rekor-key" -> rekorKeys.add(NoteKey.parse(args[++i]));
                        case "--require-rekor" -> requireRekor = true;
                        case "--json" -> json = true;
                        case "--package" -> {
                            if (!withPackage) {
                                return null;
                            }
                            packageFile = Path.of(args[++i]);
                        }
                        default -> {
                            if (file != null || args[i].startsWith("--")) {
                                return null;
                            }
                            file = Path.of(args[i]);
                        }
                    }
                }
            } catch (ArrayIndexOutOfBoundsException | IOException | IllegalArgumentException e) {
                return null;
            }
            if (file == null) {
                return null;
            }
            return new Options(publicKey, witnesses, required, keysFile, timestampCertificates, requireTimestamp,
                    rekorKeys, requireRekor, json, packageFile, file);
        }
    }

    private static PackageReport verifyPackage(JsonNode pkg, JsonNode keys, List<X509Certificate> certificates,
                                               List<NoteKey> rekorKeys, Options options) {
        return PackageVerifier.verify(pkg, options.publicKey(), keys, options.witnesses(),
                options.required() == null ? options.witnesses().size() : options.required(),
                new PackageVerifier.TimestampPolicy(certificates, options.requireTimestamp()),
                new PackageVerifier.RekorPolicy(rekorKeys, options.requireRekor()));
    }

    private static int report(String[] args, PrintStream out, PrintStream err) {
        Options options = Options.parse(args, true);
        if (options == null || options.packageFile() == null) {
            err.println(USAGE_TEXT);
            return USAGE;
        }
        JsonNode report;
        JsonNode pkg;
        JsonNode keys;
        List<X509Certificate> certificates;
        Path reading = options.file();
        try {
            report = JSON.readTree(Files.readAllBytes(options.file()));
            reading = options.packageFile();
            pkg = JSON.readTree(Files.readAllBytes(options.packageFile()));
            reading = options.keysFile();
            keys = options.keysFile() == null ? null : JSON.readTree(Files.readAllBytes(options.keysFile()));
            reading = options.timestampCertificates();
            certificates = options.timestampCertificates() == null ? List.of()
                    : TimestampToken.readCertificates(Files.readAllBytes(options.timestampCertificates()));
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            err.println("Cannot read " + reading + ": " + e.getMessage());
            return USAGE;
        }
        return finish(pkg, report, keys, certificates, options.rekorKeys(), options, List.of(), out);
    }

    private static int index(String[] args, PrintStream out, PrintStream err) {
        Options options = Options.parse(args, true);
        if (options == null) {
            err.println(USAGE_TEXT);
            return USAGE;
        }
        JsonNode proof;
        JsonNode pkg;
        JsonNode keys;
        Path reading = options.file();
        try {
            proof = JSON.readTree(Files.readAllBytes(options.file()));
            reading = options.packageFile();
            pkg = options.packageFile() == null ? null : JSON.readTree(Files.readAllBytes(options.packageFile()));
            reading = options.keysFile();
            keys = options.keysFile() == null ? null : JSON.readTree(Files.readAllBytes(options.keysFile()));
        } catch (IOException | JacksonException e) {
            err.println("Cannot read " + reading + ": " + e.getMessage());
            return USAGE;
        }
        IndexProofVerifier.IndexCheck check = IndexProofVerifier.verify(proof, options.publicKey(), keys, pkg);
        if (options.json()) {
            out.println(JSON.writeValueAsString(check));
        } else {
            out.println("Nexusphere Ledger index proof");
            out.println("  Term        : " + check.term().entrySet().stream().map(e -> String.valueOf(e.getValue()))
                    .collect(Collectors.joining(" ")));
            out.println("  Index       : checkpoint " + check.sequence() + ", head " + check.headHash());
            out.println("  Signed by   : " + check.keyId() + (options.publicKey() == null
                    ? " (taken from the proof; pass --public-key to pin the ledger's key)" : ""));
            out.println("  Finding     : " + (check.present()
                    ? check.count() + " entries, sequences " + check.firstSequence() + " to " + check.lastSequence()
                    : "no entry up to sequence " + check.sequence()));
            if (pkg != null) {
                out.println("  Package     : checkpoint " + pkg.path("checkpoint").path("sequence").asLong());
            }
            if (check.valid()) {
                out.println("Result: VALID");
            } else {
                out.println("Result: INVALID");
                check.problems().forEach(problem -> out.println("  - " + problem));
            }
        }
        return check.valid() ? VALID : INVALID;
    }

    private static int dispute(String[] args, PrintStream out, PrintStream err) {
        Options options = Options.parse(args, false);
        if (options == null) {
            err.println(USAGE_TEXT);
            return USAGE;
        }
        Map<String, byte[]> files = new HashMap<>();
        JsonNode pkg;
        JsonNode report;
        JsonNode keys;
        List<X509Certificate> certificates;
        List<NoteKey> rekorKeys = new ArrayList<>(options.rekorKeys());
        List<NoteKey> witnesses = new ArrayList<>(options.witnesses());
        List<String> notes = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(options.file()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                files.put(entry.getName(), zip.readNBytes(MAX_FILE + 1));
                if (files.get(entry.getName()).length > MAX_FILE) {
                    throw new IOException(entry.getName() + " is larger than " + MAX_FILE + " bytes");
                }
            }
            if (!files.containsKey("package.json") || !files.containsKey("report.json")) {
                throw new IOException("it has no package.json and report.json");
            }
            pkg = JSON.readTree(files.get("package.json"));
            report = JSON.readTree(files.get("report.json"));
            keys = options.keysFile() != null ? JSON.readTree(Files.readAllBytes(options.keysFile()))
                    : files.containsKey("keys.json") ? JSON.readTree(files.get("keys.json")) : null;
            if (options.timestampCertificates() != null) {
                certificates = TimestampToken.readCertificates(Files.readAllBytes(options.timestampCertificates()));
            } else if (files.containsKey("timestamp-authority.pem")) {
                certificates = TimestampToken.readCertificates(files.get("timestamp-authority.pem"));
                notes.add("the timestamp authority chain");
            } else {
                certificates = List.of();
            }
            if (rekorKeys.isEmpty() && files.containsKey("rekor.json")) {
                String key = JSON.readTree(files.get("rekor.json")).path("key").asString(null);
                if (key != null) {
                    rekorKeys.add(NoteKey.parse(key));
                    notes.add("the Rekor log key");
                }
            }
            if (witnesses.isEmpty() && files.containsKey("witnesses.txt")) {
                for (String line : new String(files.get("witnesses.txt"), StandardCharsets.UTF_8).split("\n")) {
                    if (!line.isBlank()) {
                        witnesses.add(NoteKey.parse(line.trim()));
                    }
                }
                notes.add("the witness keys");
            }
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            err.println("Cannot read " + options.file() + ": " + e.getMessage());
            return USAGE;
        }
        Options effective = new Options(options.publicKey(), witnesses,
                options.required() == null && options.witnesses().isEmpty() ? Integer.valueOf(0) : options.required(),
                options.keysFile(), options.timestampCertificates(), options.requireTimestamp(), rekorKeys,
                options.requireRekor(), options.json(), null, options.file());
        if (options.publicKey() == null) {
            notes.add("no ledger key; pass --public-key");
        }
        return finish(pkg, report, keys, certificates, rekorKeys, effective, notes, out);
    }

    private static int finish(JsonNode pkg, JsonNode report, JsonNode keys, List<X509Certificate> certificates,
                              List<NoteKey> rekorKeys, Options options, List<String> notes, PrintStream out) {
        PackageReport packageReport = verifyPackage(pkg, keys, certificates, rekorKeys, options);
        ReportVerifier.ReportCheck check = ReportVerifier.verify(report, pkg, options.publicKey(), keys);
        boolean valid = packageReport.valid() && check.valid();
        if (options.json()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", valid);
            result.put("package", packageReport);
            result.put("report", check);
            result.put("takenFromArchive", notes);
            out.println(JSON.writeValueAsString(result));
        } else {
            print(packageReport, !certificates.isEmpty(), !rekorKeys.isEmpty(), out);
            out.println("Nexusphere Ledger report");
            out.println("  Report      : " + check.report() + " (" + check.title() + ")");
            out.println("  Signed by   : " + check.keyId());
            out.println("  Evidence    : checkpoint " + check.checkpointSequence() + ", " + check.entries()
                    + " entries");
            out.println("  Sections    : " + check.statuses().entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey().replace('_', ' ').toLowerCase())
                    .collect(Collectors.joining(", ")));
            if (!notes.isEmpty()) {
                out.println("  Archive     : taken from the archive itself: " + String.join(", ", notes));
            }
            if (check.valid()) {
                out.println("Result: VALID");
            } else {
                out.println("Result: INVALID");
                check.problems().forEach(problem -> out.println("  - " + problem));
            }
        }
        return valid ? VALID : INVALID;
    }

    private static void print(PackageReport r, boolean timestampPinned, boolean rekorPinned, PrintStream out) {
        out.println("Nexusphere Ledger evidence package");
        out.println("  Signing key : " + r.keyId() + (r.pinnedKeyId() == null
                ? " (taken from the package; pass --public-key to pin the ledger's key)"
                : r.pinnedKeyId().equals(r.keyId()) ? " (pinned)"
                : " (reached from pinned key " + r.pinnedKeyId() + " through signed key rotations)"));
        out.println("  Checkpoint  : sequence " + r.checkpointSequence() + ", signed at " + r.checkpointCreatedAt());
        if (!r.complianceProfiles().isEmpty()) {
            out.println("  Compliance  : " + r.complianceProfiles().stream()
                    .map(p -> p.id() + " (" + p.digest().substring(0, 12) + ")").collect(Collectors.joining(", "))
                    + ", signed in the checkpoint");
        }
        if (r.timestampedAt() != null) {
            out.println("  Timestamp   : " + r.timestampedAt() + " by " + r.timestampAuthority() + (timestampPinned
                    ? " (pinned)" : " (pass --timestamp-certificates to pin the timestamp authority)"));
        }
        if (r.rekorLogIndex() != null) {
            out.println("  Rekor       : entry " + r.rekorLogIndex() + " in " + r.rekorOrigin() + (rekorPinned
                    ? " (pinned)" : " (pass --rekor-key to pin the Rekor log)"));
        }
        out.println("  Anchor      : " + (r.anchorSequence() == null ? "genesis" : "checkpoint " + r.anchorSequence()));
        out.println("  Chain       : " + r.checkedLinks() + " links from sequence " + r.firstSequence());
        out.println("  Disclosed   : " + r.disclosedEntries() + " entries"
                + (r.agentId() == null ? "" : ", agent " + r.agentId())
                + (r.principalId() == null ? "" : ", principal " + r.principalId()));
        if (r.disclosedPrompts() > 0) {
            out.println("  Prompts     : " + r.disclosedPrompts() + " prompt texts match the hashes their entries name");
        }
        if (r.disclosedPolicies() > 0) {
            out.println("  Policies    : " + r.disclosedPolicies()
                    + " policy set texts match the hashes their entries name");
        }
        if (r.logTreeSize() != null) {
            out.println("  Log         : " + r.logOrigin() + ", " + r.logTreeSize() + " entries, "
                    + r.provenEntries() + " disclosed entries proven");
            out.println("  Receipts    : " + r.receiptedEntries() + " SCITT statements with receipts");
            out.println("  Witnesses   : " + (r.witnesses().isEmpty() ? "none" : String.join(", ", r.witnesses())));
        }
        if (!r.revokedKeys().isEmpty()) {
            out.println("  Revoked     : " + String.join(", ", r.revokedKeys())
                    + (r.valid() ? " (witnesses prove the log checkpoint predates the compromise)" : ""));
        }
        if (r.valid()) {
            out.println("Result: VALID");
        } else {
            out.println("Result: INVALID");
            r.problems().forEach(problem -> out.println("  - " + problem));
        }
    }
}
