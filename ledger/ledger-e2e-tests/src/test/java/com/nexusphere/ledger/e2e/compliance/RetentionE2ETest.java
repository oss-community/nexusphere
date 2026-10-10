package com.nexusphere.ledger.e2e.compliance;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionE2ETest {

    private PostgreSQLContainer postgres;

    @BeforeEach
    void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
    }

    @AfterEach
    void stop() {
        postgres.stop();
    }

    private StandaloneLedger ledger(Path directory, String profile) throws IOException {
        Files.writeString(directory.resolve("short.json"), profile);
        return StandaloneLedger.start(postgres, freePort(), SigningKeys.generate(),
                "--ledger.compliance.profiles=short", "--ledger.compliance.profile-directory=" + directory);
    }

    private static Map<String, Object> call(String agent, String principal, Instant occurredAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agent);
        body.put("principalId", principal);
        body.put("action", "tools/call");
        body.put("target", "read_file");
        body.put("outcome", "SUCCEEDED");
        body.put("occurredAt", occurredAt.toString());
        return body;
    }

    private static boolean erased(LedgerClient client, JsonNode entry) {
        return client.get("/api/v1/evidence/" + entry.path("id").asString()).json().path("erased").asBoolean();
    }

    @Test
    void personalDataPastTheMaximumRetentionIsErasedUnlessHeld(@TempDir Path directory) throws IOException {
        try (StandaloneLedger ledger = ledger(directory, """
                {"id": "short", "version": 1, "name": "Short",
                 "retention": [{"actions": "*", "minimum": "P1D", "maximum": "P1Y"}]}
                """)) {
            LedgerClient client = ledger.client();
            String agent = "agent-" + UUID.randomUUID();
            Instant old = Instant.parse("2019-01-01T00:00:00Z");
            JsonNode expired = client.recordEvidence(call(agent, "alice", old));
            JsonNode held = client.recordEvidence(call(agent, "bob", old));
            JsonNode recent = client.recordEvidence(call(agent, "alice", Instant.now()));
            assertThat(client.post("/api/v1/legal-holds", LedgerClient.json(Map.of("principalId", "bob",
                    "reason", "dispute 7"))).status()).isEqualTo(201);

            JsonNode sweep = client.post("/api/v1/retention/sweep").json();

            assertThat(sweep.path("expiredEntries").asLong()).isEqualTo(1);
            assertThat(erased(client, expired)).isTrue();
            assertThat(erased(client, held)).isFalse();
            assertThat(erased(client, recent)).isFalse();
            assertThat(client.post("/api/v1/retention/sweep").json().path("expiredEntries").asLong()).isZero();
            JsonNode compliance = client.get("/public/v1/compliance").json();
            assertThat(compliance.path("effective").path("retention").get(0).path("maximum").asString())
                    .isEqualTo("P1Y");
            assertThat(client.post("/api/v1/checkpoints").status()).isEqualTo(200);
            JsonNode pkg = client.post("/api/v1/packages", LedgerClient.json(Map.of("agentId", agent))).json();
            String key = client.get("/api/v1/keys").json().get(0).path("publicKey").asString();
            PackageReport report = PackageVerifier.verify(pkg, key);
            assertThat(report.valid()).as(report.problems().toString()).isTrue();
            assertThat(report.disclosedEntries()).isEqualTo(3);
        }
    }

    @Test
    void aPendingErasureCompletesOnceTheRetentionEnds(@TempDir Path directory) throws Exception {
        try (StandaloneLedger ledger = ledger(directory, """
                {"id": "short", "version": 1, "name": "Short", "retention": [{"actions": "*", "minimum": "P1D"}]}
                """)) {
            LedgerClient client = ledger.client();
            Instant occurredAt = Instant.now().minus(Duration.ofDays(1)).plusSeconds(5);
            JsonNode entry = client.recordEvidence(call("agent-" + UUID.randomUUID(), "carol", occurredAt));

            JsonNode erasure = client.post("/api/v1/principals/carol/erasure",
                    LedgerClient.json(Map.of("reason", "request of the principal"))).json();

            assertThat(erasure.path("completed").asBoolean()).isFalse();
            assertThat(erasure.path("retainedEntries").asLong()).isEqualTo(1);
            Instant until = Instant.parse(erasure.path("retainedUntil").asString());
            assertThat(client.post("/api/v1/retention/sweep").json().path("erasures").isEmpty()).isTrue();
            Thread.sleep(Math.max(0, Duration.between(Instant.now(), until).toMillis()) + 500);

            JsonNode sweep = client.post("/api/v1/retention/sweep").json();

            assertThat(sweep.path("erasures")).singleElement()
                    .satisfies(done -> assertThat(done.path("completed").asBoolean()).isTrue());
            assertThat(erased(client, entry)).isTrue();
            assertThat(client.post("/api/v1/principals/carol/erasure", "{}").status()).isEqualTo(404);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
