package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.mandate.KeyResolver;
import com.nexusphere.ledger.mandate.MandateCheck;
import com.nexusphere.ledger.mandate.MandateClaims;
import com.nexusphere.ledger.mandate.MandateVerifier;
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

final class MandateCli {

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private MandateCli() {
    }

    static int run(String[] args, PrintStream out, PrintStream err, String usage) {
        return run(args, System.in, out, err, usage);
    }

    static int run(String[] args, InputStream in, PrintStream out, PrintStream err, String usage) {
        List<String> issuers = new ArrayList<>();
        String audience = null;
        String action = null;
        String target = null;
        String publicKey = null;
        boolean skipStatus = false;
        boolean json = false;
        String source = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--issuer" -> issuers.add(args[++i]);
                    case "--audience" -> audience = args[++i];
                    case "--action" -> action = args[++i];
                    case "--target" -> target = args[++i];
                    case "--public-key" -> publicKey = args[++i].trim();
                    case "--public-key-file" -> publicKey = Files.readString(Path.of(args[++i])).trim();
                    case "--skip-status" -> skipStatus = true;
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
        if (source == null || issuers.isEmpty() || (action == null) != (target == null)) {
            err.println(usage);
            return VerifierCli.USAGE;
        }
        String token;
        try {
            token = read(source, in);
        } catch (IOException e) {
            err.println("Cannot read " + source + ": " + e.getMessage());
            return VerifierCli.USAGE;
        }
        MandateVerifier.Builder builder = MandateVerifier.builder().audience(audience);
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
        if (skipStatus) {
            builder.skipStatus();
        }
        MandateVerifier verifier = builder.build();
        MandateCheck check = action == null ? verifier.verify(token) : verifier.verify(token, action, target);
        if (json) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("valid", check.valid());
            report.put("problems", check.problems());
            report.put("claims", check.claims() == null ? null : check.claims().toPayload());
            out.println(JSON.writeValueAsString(report));
        } else {
            print(check, action, target, skipStatus, out);
        }
        return check.valid() ? VerifierCli.VALID : VerifierCli.INVALID;
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

    private static void print(MandateCheck check, String action, String target, boolean skipStatus, PrintStream out) {
        out.println("Nexusphere Ledger mandate");
        MandateClaims c = check.claims();
        if (c != null) {
            out.println("  Mandate     : " + c.mandateId() + " from grant " + c.grantId());
            out.println("  Issuer      : " + c.issuer());
            out.println("  Agent       : " + c.agentId() + " for principal " + c.principalId());
            out.println("  Audience    : " + (c.audience() == null ? "any" : c.audience()));
            out.println("  Allows      : " + String.join(", ", c.actions()) + " on " + String.join(", ", c.targets())
                    + (c.maxUses() == null ? "" : ", at most " + c.maxUses() + " uses"));
            out.println("  Valid       : " + c.notBefore() + " to " + c.expiresAt());
            out.println("  Status      : " + (skipStatus ? "not checked" : "index " + c.statusIndex() + " in "
                    + c.statusListUrl()));
        }
        if (action != null) {
            out.println("  Checked     : " + action + " on " + target);
        }
        if (check.valid()) {
            out.println("Result: VALID");
        } else {
            out.println("Result: INVALID");
            check.problems().forEach(p -> out.println("  - " + p.code() + ": " + p.message()));
        }
    }
}
