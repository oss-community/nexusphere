package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.mandate.KeyBinding;
import com.nexusphere.ledger.mandate.MandateClaims;
import com.nexusphere.ledger.mandate.Mandates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MandateCliTest {

    private static final String ISSUER = "https://ledger.acme.test";
    private static final KeyPair KEYS = SigningKeys.generate();
    private static final String PUBLIC_KEY = SigningKeys.encode(KEYS.getPublic());

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private static String token() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return Mandates.issue(new MandateClaims(ISSUER, UUID.randomUUID(), "invoice-agent", "alice", null,
                List.of("a2a/send"), List.of("orders/*"), null, UUID.randomUUID(), "ef".repeat(32), now, now,
                now.plusSeconds(600), ISSUER + "/public/v1/mandates/status", 0), SigningKeys.keyIdOf(KEYS.getPublic()),
                KEYS.getPrivate());
    }

    private int run(String... args) {
        return VerifierCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void aMandateFromAFileIsVerifiedWithAPinnedKey(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mandate.jwt");
        Files.writeString(file, token() + "\n");

        int code = run("mandate", "--issuer", ISSUER, "--public-key", PUBLIC_KEY, "--skip-status", "--action",
                "a2a/send", "--target", "orders/create", file.toString());

        assertThat(code).isEqualTo(VerifierCli.VALID);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Result: VALID").contains("alice");
    }

    @Test
    void anUncoveredActionIsInvalidAndReportedAsJson() {
        int code = run("mandate", "--issuer", ISSUER, "--public-key", PUBLIC_KEY, "--skip-status", "--json",
                "--action", "a2a/send", "--target", "invoices/pay", token());

        assertThat(code).isEqualTo(VerifierCli.INVALID);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"valid\" : false").contains("NOT_COVERED");
    }

    @Test
    void aTokenIsReadFromStandardInput() {
        int code = MandateCli.run(new String[]{"--issuer", ISSUER, "--public-key", PUBLIC_KEY, "--skip-status", "-"},
                new ByteArrayInputStream(token().getBytes(StandardCharsets.US_ASCII)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                "usage");

        assertThat(code).isEqualTo(VerifierCli.VALID);
    }

    @Test
    void aKeyBoundMandateIsCheckedAgainstTheNonce() {
        KeyPair agent = SigningKeys.generate();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String bound = Mandates.issue(new MandateClaims(ISSUER, UUID.randomUUID(), "invoice-agent", "alice",
                "https://supplier.test", List.of("a2a/send"), List.of("orders/*"), null, UUID.randomUUID(),
                "ef".repeat(32), now, now, now.plusSeconds(600), ISSUER + "/public/v1/mandates/status", 0,
                agent.getPublic()), SigningKeys.keyIdOf(KEYS.getPublic()), KEYS.getPrivate());
        String presented = KeyBinding.present(bound, agent.getPrivate(), "https://supplier.test", "ab".repeat(32), now);

        int valid = run("mandate", "--issuer", ISSUER, "--public-key", PUBLIC_KEY, "--skip-status", "--nonce",
                "ab".repeat(32), presented);
        String report = out.toString(StandardCharsets.UTF_8);
        int replayed = run("mandate", "--issuer", ISSUER, "--public-key", PUBLIC_KEY, "--skip-status", "--nonce",
                "cd".repeat(32), presented);
        int unbound = run("mandate", "--issuer", ISSUER, "--public-key", PUBLIC_KEY, "--skip-status",
                "--require-key-binding", token());

        assertThat(valid).isEqualTo(VerifierCli.VALID);
        assertThat(report).contains("Agent key   : " + SigningKeys.keyIdOf(agent.getPublic()));
        assertThat(replayed).isEqualTo(VerifierCli.INVALID);
        assertThat(unbound).isEqualTo(VerifierCli.INVALID);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("KEY_BINDING_INVALID").contains("KEY_NOT_BOUND");
    }

    @Test
    void missingIssuerOrHalfACheckIsAUsageError() {
        assertThat(run("mandate", token())).isEqualTo(VerifierCli.USAGE);
        assertThat(run("mandate", "--issuer", ISSUER, "--action", "a2a/send", token())).isEqualTo(VerifierCli.USAGE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("nexusphere-ledger-verify mandate");
    }
}
