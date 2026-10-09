package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.NoteKey;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class VerifierCli {

    public static final int VALID = 0;
    public static final int INVALID = 1;
    public static final int USAGE = 2;

    private static final String USAGE_TEXT = """
            Usage: nexusphere-ledger-verify [--public-key <base64 X.509 Ed25519 key> | --public-key-file <file>] \
            [--keys <keys.json>] [--witness <verifier key>]... [--witnesses-required <n>] [--json] <package.json>
                   nexusphere-ledger-verify mandate --issuer <url> [--issuer <url>] [--audience <aud>] \
            [--action <action> --target <target>] [--public-key <key> | --public-key-file <file>] [--skip-status] \
            [--json] <token | token file | ->
                   nexusphere-ledger-verify statement [--public-key <key> | --public-key-file <file>] \
            [--receipt <receipt.cose>] <statement.cose>""";

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
        if (args.length > 0 && "statement".equals(args[0])) {
            return StatementCli.run(Arrays.copyOfRange(args, 1, args.length), out, err, USAGE_TEXT);
        }
        String publicKey = null;
        List<NoteKey> witnesses = new ArrayList<>();
        Integer required = null;
        Path keysFile = null;
        boolean json = false;
        Path file = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--public-key" -> publicKey = args[++i].trim();
                    case "--public-key-file" -> publicKey = Files.readString(Path.of(args[++i])).trim();
                    case "--keys" -> keysFile = Path.of(args[++i]);
                    case "--witness" -> witnesses.add(NoteKey.parse(args[++i]));
                    case "--witnesses-required" -> required = Integer.parseInt(args[++i]);
                    case "--json" -> json = true;
                    default -> {
                        if (file != null || args[i].startsWith("--")) {
                            err.println(USAGE_TEXT);
                            return USAGE;
                        }
                        file = Path.of(args[i]);
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException | IOException | IllegalArgumentException e) {
            err.println(USAGE_TEXT);
            return USAGE;
        }
        if (file == null) {
            err.println(USAGE_TEXT);
            return USAGE;
        }
        JsonNode pkg;
        JsonNode keys = null;
        Path reading = file;
        try {
            pkg = JSON.readTree(Files.readAllBytes(file));
            if (keysFile != null) {
                reading = keysFile;
                keys = JSON.readTree(Files.readAllBytes(keysFile));
            }
        } catch (IOException | JacksonException e) {
            err.println("Cannot read " + reading + ": " + e.getMessage());
            return USAGE;
        }
        PackageReport report = PackageVerifier.verify(pkg, publicKey, keys, witnesses,
                required == null ? witnesses.size() : required);
        if (json) {
            out.println(JSON.writeValueAsString(report));
        } else {
            print(report, out);
        }
        return report.valid() ? VALID : INVALID;
    }

    private static void print(PackageReport r, PrintStream out) {
        out.println("Nexusphere Ledger evidence package");
        out.println("  Signing key : " + r.keyId() + (r.pinnedKeyId() == null
                ? " (taken from the package; pass --public-key to pin the ledger's key)"
                : r.pinnedKeyId().equals(r.keyId()) ? " (pinned)"
                : " (reached from pinned key " + r.pinnedKeyId() + " through signed key rotations)"));
        out.println("  Checkpoint  : sequence " + r.checkpointSequence() + ", signed at " + r.checkpointCreatedAt());
        out.println("  Anchor      : " + (r.anchorSequence() == null ? "genesis" : "checkpoint " + r.anchorSequence()));
        out.println("  Chain       : " + r.checkedLinks() + " links from sequence " + r.firstSequence());
        out.println("  Disclosed   : " + r.disclosedEntries() + " entries"
                + (r.agentId() == null ? "" : ", agent " + r.agentId())
                + (r.principalId() == null ? "" : ", principal " + r.principalId()));
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
