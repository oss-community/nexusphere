package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.mandate.KeyResolver;
import com.nexusphere.ledger.mandate.TrackRecordCheck;
import com.nexusphere.ledger.mandate.TrackRecordClaims;
import com.nexusphere.ledger.mandate.TrackRecordVerifier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

final class TrackRecordCli {

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private TrackRecordCli() {
    }

    static int run(String[] args, PrintStream out, PrintStream err, String usage) {
        return run(args, System.in, out, err, usage);
    }

    static int run(String[] args, InputStream in, PrintStream out, PrintStream err, String usage) {
        List<String> issuers = new ArrayList<>();
        String audience = null;
        String publicKey = null;
        boolean requireKeyBinding = false;
        String nonce = null;
        Path indexProof = null;
        boolean json = false;
        String source = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--issuer" -> issuers.add(args[++i]);
                    case "--audience" -> audience = args[++i];
                    case "--public-key" -> publicKey = args[++i].trim();
                    case "--public-key-file" -> publicKey = Files.readString(Path.of(args[++i])).trim();
                    case "--nonce" -> nonce = args[++i];
                    case "--require-key-binding" -> requireKeyBinding = true;
                    case "--index-proof" -> indexProof = Path.of(args[++i]);
                    case "--json" -> json = true;
                    default -> {
                        if (source != null || args[i].startsWith("--")) {
                            err.println(usage);
                            return VerifierCli.USAGE;
                        }
                        source = args[i];
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException | IOException e) {
            err.println(usage);
            return VerifierCli.USAGE;
        }
        if (source == null || issuers.isEmpty()) {
            err.println(usage);
            return VerifierCli.USAGE;
        }
        String token;
        JsonNode proof;
        String reading = source;
        try {
            token = read(source, in);
            reading = String.valueOf(indexProof);
            proof = indexProof == null ? null : JSON.readTree(Files.readAllBytes(indexProof));
        } catch (IOException | JacksonException e) {
            err.println("Cannot read " + reading + ": " + e.getMessage());
            return VerifierCli.USAGE;
        }
        TrackRecordVerifier.Builder builder = TrackRecordVerifier.builder().audience(audience);
        issuers.forEach(builder::trustIssuer);
        if (publicKey != null) {
            PublicKey key;
            try {
                key = SigningKeys.decodePublic(publicKey);
            } catch (RuntimeException e) {
                err.println("The public key is not a base64 X.509 Ed25519 key");
                return VerifierCli.USAGE;
            }
            List<KeyResolver> pinned = issuers.stream().map(i -> KeyResolver.fixed(stripSlash(i), key)).toList();
            builder.keys((issuer, kid) -> pinned.stream().map(r -> r.resolve(issuer, kid)).flatMap(Optional::stream)
                    .findFirst());
        }
        if (requireKeyBinding) {
            builder.requireKeyBinding();
        }
        TrackRecordCheck check = builder.build().verifyBound(token, nonce);
        List<String> problems = new ArrayList<>();
        check.problems().forEach(p -> problems.add(p.code() + ": " + p.message()));
        IndexProofVerifier.IndexCheck index = null;
        if (proof != null) {
            index = IndexProofVerifier.verify(proof, publicKey, null);
            index.problems().forEach(p -> problems.add("INDEX_PROOF: " + p));
            if (check.claims() != null) {
                IndexProofVerifier.matchTrackRecord(check.claims(), proof, index)
                        .forEach(p -> problems.add("INDEX_MISMATCH: " + p));
            }
        }
        boolean valid = problems.isEmpty();
        if (json) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("valid", valid);
            report.put("problems", problems);
            report.put("claims", check.claims() == null ? null : check.claims().toPayload());
            report.put("indexProof", index);
            out.println(JSON.writeValueAsString(report));
        } else {
            print(check.claims(), index, valid, problems, out);
        }
        return valid ? VerifierCli.VALID : VerifierCli.INVALID;
    }

    private static String read(String source, InputStream in) throws IOException {
        if ("-".equals(source)) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }
        Path path = Path.of(source);
        return Files.isRegularFile(path) ? Files.readString(path).trim() : source.trim();
    }

    private static String stripSlash(String issuer) {
        return issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }

    private static String counts(Map<String, Long> counts) {
        return counts == null ? "withheld" : counts.isEmpty() ? "none" : counts.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
    }

    private static void print(TrackRecordClaims c, IndexProofVerifier.IndexCheck index, boolean valid,
                              List<String> problems, PrintStream out) {
        out.println("Nexusphere Ledger agent track record");
        if (c != null) {
            out.println("  Credential  : " + c.id());
            out.println("  Issuer      : " + c.issuer());
            out.println("  Agent       : " + c.agentId());
            out.println("  Audience    : " + (c.audience() == null ? "any" : c.audience()));
            out.println("  Valid       : " + c.issuedAt() + " to " + c.expiresAt());
            out.println("  Checkpoint  : " + c.checkpointSequence() + ", index root " + c.indexRoot());
            out.println("  Entries     : " + c.total() + (c.firstSequence() == null ? ""
                    : ", sequences " + c.firstSequence() + " to " + c.lastSequence() + ", " + c.firstAt() + " to "
                    + c.lastAt()));
            out.println("  Outcomes    : " + counts(c.outcomes()));
            out.println("  Principals  : " + (c.principals() == null ? "withheld" : c.principals()));
            out.println("  Actions     : " + counts(c.actions()));
            out.println("  Agent key   : " + (c.bound() ? SigningKeys.keyIdOf(c.holderKey()) : "none"));
        }
        if (index != null) {
            out.println("  Index proof : " + (index.present() ? index.count() : 0) + " entries at checkpoint "
                    + index.sequence());
        }
        if (valid) {
            out.println("Result: VALID");
        } else {
            out.println("Result: INVALID");
            problems.forEach(p -> out.println("  - " + p));
        }
    }
}
