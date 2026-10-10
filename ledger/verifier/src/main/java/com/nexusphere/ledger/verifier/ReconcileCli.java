package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.mandate.ExchangeStatement;
import com.nexusphere.ledger.mandate.HttpFetcher;
import com.nexusphere.ledger.mandate.Jws;
import com.nexusphere.ledger.mandate.JwksKeyResolver;
import com.nexusphere.ledger.mandate.KeyResolver;
import com.nexusphere.ledger.mandate.Reconciliation;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReconcileCli {

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private ReconcileCli() {
    }

    static int run(String[] args, PrintStream out, PrintStream err, String usage) {
        String ourKey = null;
        String peerKey = null;
        boolean json = false;
        List<String> files = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--public-key" -> ourKey = args[++i].trim();
                    case "--public-key-file" -> ourKey = Files.readString(Path.of(args[++i])).trim();
                    case "--peer-public-key" -> peerKey = args[++i].trim();
                    case "--peer-public-key-file" -> peerKey = Files.readString(Path.of(args[++i])).trim();
                    case "--json" -> json = true;
                    default -> {
                        if (args[i].startsWith("--")) {
                            err.println(usage);
                            return VerifierCli.USAGE;
                        }
                        files.add(args[i]);
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException | IOException e) {
            err.println(usage);
            return VerifierCli.USAGE;
        }
        if (files.size() != 2) {
            err.println(usage);
            return VerifierCli.USAGE;
        }
        String[] tokens = new String[2];
        for (int i = 0; i < 2; i++) {
            try {
                Path path = Path.of(files.get(i));
                tokens[i] = Files.isRegularFile(path) ? Files.readString(path).trim() : files.get(i).trim();
            } catch (IOException e) {
                err.println("Cannot read " + files.get(i) + ": " + e.getMessage());
                return VerifierCli.USAGE;
            }
        }
        JwksKeyResolver jwks = new JwksKeyResolver(HttpFetcher.create(Duration.ofSeconds(10)), Clock.systemUTC(),
                Duration.ZERO);
        List<String> problems = new ArrayList<>();
        ExchangeStatement ours = verified(tokens[0], ourKey, jwks, "ours", problems);
        ExchangeStatement theirs = verified(tokens[1], peerKey, jwks, "theirs", problems);
        Reconciliation reconciliation = null;
        if (ours != null && theirs != null) {
            try {
                reconciliation = Reconciliation.of(ours, theirs);
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
        boolean valid = problems.isEmpty() && reconciliation != null && reconciliation.reconciled();
        if (json) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("valid", valid);
            report.put("problems", problems);
            report.put("reconciliation", reconciliation);
            out.println(JSON.writeValueAsString(report));
        } else {
            print(ours, theirs, reconciliation, problems, ourKey == null || peerKey == null, valid, out);
        }
        return valid ? VerifierCli.VALID : VerifierCli.INVALID;
    }

    private static ExchangeStatement verified(String token, String pinned, KeyResolver jwks, String name,
                                              List<String> problems) {
        try {
            String issuer = Jws.parse(token).payload().path("iss").asString("");
            KeyResolver keys = pinned == null ? jwks : KeyResolver.fixed(issuer, SigningKeys.decodePublic(pinned));
            return ExchangeStatement.verified(token, issuer, keys);
        } catch (RuntimeException e) {
            problems.add("The " + name + " statement cannot be trusted: " + e.getMessage());
            return null;
        }
    }

    private static void print(ExchangeStatement ours, ExchangeStatement theirs, Reconciliation r,
                              List<String> problems, boolean fetched, boolean valid, PrintStream out) {
        out.println("Nexusphere Ledger A2A reconciliation");
        if (ours != null) {
            out.println("  Ours        : " + ours.issuer() + ", " + ours.exchanges().size() + " exchanges");
        }
        if (theirs != null) {
            out.println("  Theirs      : " + theirs.issuer() + ", " + theirs.exchanges().size() + " exchanges");
        }
        if (fetched) {
            out.println("  Keys        : fetched from the issuers; pass --public-key and --peer-public-key to pin them");
        }
        if (r != null) {
            out.println("  Window      : " + r.from() + " to " + r.to());
            out.println("  Matched     : " + r.matched());
            out.println("  Undelivered : " + r.undelivered());
        }
        if (valid) {
            out.println("Result: RECONCILED");
            return;
        }
        out.println("Result: NOT RECONCILED");
        problems.forEach(p -> out.println("  - " + p));
        if (r != null) {
            r.discrepancies().forEach(d -> out.println("  - " + d.kind() + " " + d.requestId() + " at "
                    + d.requestedAt() + ": " + switch (d.kind()) {
                        case Reconciliation.NOT_SENT -> "received as from " + d.sender() + ", which never sent it";
                        case Reconciliation.NOT_RECEIVED -> "sent by " + d.sender() + ", never recorded as received";
                        default -> "sent by " + d.sender() + ", differs in " + String.join(", ", d.fields());
                    }));
        }
    }
}
