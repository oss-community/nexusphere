package com.nexusphere.ledger.verifier;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VerifierCli {

    public static final int VALID = 0;
    public static final int INVALID = 1;
    public static final int USAGE = 2;

    private static final String USAGE_TEXT = """
            Usage: nexusphere-ledger-verify [--public-key <base64 X.509 Ed25519 key> | --public-key-file <file>] \
            [--json] <package.json>""";

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private VerifierCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        String publicKey = null;
        boolean json = false;
        Path file = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--public-key" -> publicKey = args[++i].trim();
                    case "--public-key-file" -> publicKey = Files.readString(Path.of(args[++i])).trim();
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
        } catch (ArrayIndexOutOfBoundsException | IOException e) {
            err.println(USAGE_TEXT);
            return USAGE;
        }
        if (file == null) {
            err.println(USAGE_TEXT);
            return USAGE;
        }
        JsonNode pkg;
        try {
            pkg = JSON.readTree(Files.readAllBytes(file));
        } catch (IOException | JacksonException e) {
            err.println("Cannot read " + file + ": " + e.getMessage());
            return USAGE;
        }
        PackageReport report = PackageVerifier.verify(pkg, publicKey);
        if (json) {
            out.println(JSON.writeValueAsString(report));
        } else {
            print(report, out);
        }
        return report.valid() ? VALID : INVALID;
    }

    private static void print(PackageReport r, PrintStream out) {
        out.println("Nexusphere Ledger evidence package");
        out.println("  Signing key : " + r.keyId() + (r.keyPinned() ? " (pinned)"
                : " (taken from the package; pass --public-key to pin the ledger's key)"));
        out.println("  Checkpoint  : sequence " + r.checkpointSequence() + ", signed at " + r.checkpointCreatedAt());
        out.println("  Anchor      : " + (r.anchorSequence() == null ? "genesis" : "checkpoint " + r.anchorSequence()));
        out.println("  Chain       : " + r.checkedLinks() + " links from sequence " + r.firstSequence());
        out.println("  Disclosed   : " + r.disclosedEntries() + " entries"
                + (r.agentId() == null ? "" : ", agent " + r.agentId())
                + (r.principalId() == null ? "" : ", principal " + r.principalId()));
        if (r.valid()) {
            out.println("Result: VALID");
        } else {
            out.println("Result: INVALID");
            r.problems().forEach(problem -> out.println("  - " + problem));
        }
    }
}
