package com.nexusphere.ledger.e2e.signing;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import com.nexusphere.ledger.mandate.MandateCheck;
import com.nexusphere.ledger.mandate.MandateVerifier;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyRotationE2ETest {

    private final KeyPair first = SigningKeys.generate();
    private final KeyPair second = SigningKeys.generate();
    private final int port = freePort();
    private PostgreSQLContainer postgres;

    @BeforeEach
    void startDatabase() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
    }

    @AfterEach
    void stopDatabase() {
        postgres.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String keyId(KeyPair keys) {
        return SigningKeys.keyIdOf(keys.getPublic());
    }

    private static void record(LedgerClient ledger, String target) {
        ledger.recordEvidence(Map.of("agentId", "agent-a", "principalId", "alice", "action", "tools/call",
                "target", target, "decision", "ALLOW", "outcome", "SUCCEEDED"));
        assertThat(ledger.post("/api/v1/checkpoints").status()).isEqualTo(200);
    }

    private static String mandate(LedgerClient ledger) {
        String agentId = "agent-" + UUID.randomUUID();
        String apiKey = ledger.post("/api/v1/agents", LedgerClient.json(Map.of("agentId", agentId,
                "name", "Agent", "ownerId", "acme"))).json().path("apiKey").asString();
        String grantId = ledger.post("/api/v1/grants", LedgerClient.json(Map.of("principalId", "alice",
                "agentId", agentId, "actions", List.of("a2a/send"), "targets", List.of("supplier/*"),
                "expiresAt", Instant.now().plus(1, ChronoUnit.HOURS).toString()))).json().path("id").asString();
        return ledger.withApiKey(apiKey).post("/api/v1/mandates", LedgerClient.json(Map.of("grantId", grantId,
                "audience", "https://supplier.test"))).json().path("token").asString();
    }

    private static List<String> actions(LedgerClient ledger) {
        List<String> actions = new ArrayList<>();
        for (JsonNode entry : ledger.get("/api/v1/evidence?limit=500").json().path("items")) {
            actions.add(entry.path("action").asString() + " " + entry.path("target").asString());
        }
        return actions;
    }

    @Test
    void anEndorsedRotationKeepsEarlierCheckpointsAndMandatesValid() {
        String token;
        try (StandaloneLedger ledger = StandaloneLedger.start(postgres, port, first)) {
            record(ledger.client(), "search");
            token = mandate(ledger.client());
        }
        try (StandaloneLedger ledger = StandaloneLedger.start(postgres, port, second,
                "--ledger.signing.previous-private-key=" + SigningKeys.encode(first.getPrivate()))) {
            LedgerClient client = ledger.client();
            record(client, "send_email");

            JsonNode keys = client.get("/api/v1/keys").json();
            assertThat(keys).hasSize(2);
            assertThat(keys.get(0).path("keyId").asString()).isEqualTo(keyId(first));
            assertThat(keys.get(0).path("status").asString()).isEqualTo("RETIRED");
            assertThat(keys.get(1).path("status").asString()).isEqualTo("ACTIVE");
            assertThat(keys.get(1).path("rotation").path("previousKeyId").asString()).isEqualTo(keyId(first));
            assertThat(keys.get(1).path("rotation").path("previousKeySignature").isString()).isTrue();
            assertThat(client.get("/public/v1/keys").json().path("keys")).hasSize(2);
            assertThat(actions(client)).contains("key/activate " + keyId(first), "key/rotate " + keyId(second));
            assertThat(client.get("/api/v1/verification").json().path("valid").asBoolean()).isTrue();

            JsonNode pkg = client.post("/api/v1/packages", "{}").json();
            PackageReport report = PackageVerifier.verify(pkg, SigningKeys.encode(first.getPublic()));
            assertThat(report.problems()).isEmpty();
            assertThat(report.keyId()).isEqualTo(keyId(second));

            MandateCheck check = MandateVerifier.builder().trustIssuer(ledger.baseUrl())
                    .audience("https://supplier.test").cacheTtl(Duration.ZERO).build().verify(token);
            assertThat(check.problems()).isEmpty();
        }
    }

    @Test
    void aNewKeyWithoutThePreviousKeyIsRefusedUnlessTheOperatorAllowsIt() {
        try (StandaloneLedger ledger = StandaloneLedger.start(postgres, port, first)) {
            record(ledger.client(), "search");
        }

        assertThatThrownBy(() -> StandaloneLedger.start(postgres, port, second))
                .hasStackTraceContaining("ledger.signing.previous-private-key");

        try (StandaloneLedger ledger = StandaloneLedger.start(postgres, port, second,
                "--ledger.signing.unendorsed-rotation=true")) {
            JsonNode rotation = ledger.client().get("/api/v1/keys").json().get(1).path("rotation");
            assertThat(rotation.path("previousKeySignature").isNull()).isTrue();
            JsonNode pkg = ledger.client().post("/api/v1/packages", "{}").json();
            assertThat(PackageVerifier.verify(pkg, SigningKeys.encode(first.getPublic())).valid()).isFalse();
            assertThat(PackageVerifier.verify(pkg, SigningKeys.encode(second.getPublic())).problems()).isEmpty();
        }
    }

    @Test
    void aRetiredKeyCannotSignAgain() {
        try (StandaloneLedger ledger = StandaloneLedger.start(postgres, port, first)) {
            record(ledger.client(), "search");
        }
        try (StandaloneLedger ignored = StandaloneLedger.start(postgres, port, second,
                "--ledger.signing.previous-private-key=" + SigningKeys.encode(first.getPrivate()))) {
            assertThat(ignored.client().get("/api/v1/keys").json()).hasSize(2);
        }

        assertThatThrownBy(() -> StandaloneLedger.start(postgres, port, first))
                .hasStackTraceContaining("was retired");
    }
}
