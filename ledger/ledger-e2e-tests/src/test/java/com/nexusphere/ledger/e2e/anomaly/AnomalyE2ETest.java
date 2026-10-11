package com.nexusphere.ledger.e2e.anomaly;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyE2ETest {

    private PostgreSQLContainer postgres;
    private final KeyPair keys = SigningKeys.generate();

    @BeforeEach
    void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
    }

    @AfterEach
    void stop() {
        postgres.stop();
    }

    private static void deny(LedgerClient client, String agentId, int times) {
        for (int i = 0; i < times; i++) {
            client.post("/api/v1/decisions", LedgerClient.json(Map.of("agentId", agentId, "principalId", "alice",
                    "action", "tools/call", "target", "bank/wire_transfer")));
        }
    }

    private static List<String> kinds(JsonNode page) {
        List<String> kinds = new ArrayList<>();
        page.path("items").forEach(a -> kinds.add(a.path("agentId").asString() + ":" + a.path("kind").asString()));
        return kinds.stream().sorted().toList();
    }

    @Test
    void aBurstOfDenialsIsDetectedRecordedExportedAndResolved() throws InterruptedException {
        try (StandaloneLedger ledger = StandaloneLedger.start(postgres, freePort(), keys,
                "--ledger.anomaly.interval=1h", "--ledger.anomaly.window=3s", "--ledger.anomaly.baseline=30s",
                "--ledger.anomaly.min-history=0s", "--ledger.anomaly.min-events=5")) {
            LedgerClient client = ledger.client();
            for (String agent : List.of("payments-agent", "quiet-agent")) {
                client.post("/api/v1/agents", LedgerClient.json(Map.of("agentId", agent, "name", agent,
                        "ownerId", "acme")));
            }
            deny(client, "payments-agent", 6);
            deny(client, "quiet-agent", 2);

            JsonNode detected = client.post("/api/v1/anomalies/detect").json();
            assertThat(kinds(detected)).containsExactly("payments-agent:DENIALS", "payments-agent:VOLUME");
            JsonNode volume = detected.path("items").get(0).path("kind").asString().equals("VOLUME")
                    ? detected.path("items").get(0) : detected.path("items").get(1);
            assertThat(volume.path("observed").asDouble()).isEqualTo(7.0);
            assertThat(volume.path("status").asString()).isEqualTo("OPEN");
            assertThat(client.post("/api/v1/anomalies/detect").json().path("items")).isEmpty();

            JsonNode evidence = client.get("/api/v1/evidence/" + volume.path("evidenceId").asString()).json();
            assertThat(evidence.path("action").asString()).isEqualTo("anomaly/detect");
            assertThat(evidence.path("agentId").asString()).isEqualTo("payments-agent");
            assertThat(evidence.path("attributes").path("anomaly.kind").asString()).isEqualTo("VOLUME");
            assertThat(evidence.path("reason").asString()).startsWith("Agent payments-agent recorded 7 entries");

            String metrics = client.get("/actuator/prometheus").body();
            assertThat(metrics).contains("ledger_anomaly_score{agent=\"payments-agent\",application=\"nexusphere-ledger\",kind=\"VOLUME\"}");
            assertThat(metrics).doesNotContain("agent=\"quiet-agent\"");

            Thread.sleep(3_500);
            assertThat(client.post("/api/v1/anomalies/detect").json().path("items")).isEmpty();
            JsonNode resolved = client.get("/api/v1/anomalies?status=RESOLVED").json();
            assertThat(kinds(resolved)).containsExactly("payments-agent:DENIALS", "payments-agent:VOLUME");
            JsonNode resolution = client.get("/api/v1/evidence/"
                    + resolved.path("items").get(0).path("resolvedEvidenceId").asString()).json();
            assertThat(resolution.path("action").asString()).isEqualTo("anomaly/resolve");
            assertThat(client.get("/actuator/prometheus").body()).doesNotContain("ledger_anomaly_score{");
            assertThat(client.get("/api/v1/anomalies?status=OPEN").json().path("items")).isEmpty();
            assertThat(client.withApiKey(null).get("/api/v1/anomalies").status()).isEqualTo(401);
            assertThat(client.get("/api/v1/verification").json().path("valid").asBoolean()).isTrue();
        }
    }

    @Test
    void aNewAgentIsNotJudgedBeforeItHasAHistory() {
        try (StandaloneLedger ledger = StandaloneLedger.start(postgres, freePort(), keys,
                "--ledger.anomaly.interval=1h", "--ledger.anomaly.min-events=5")) {
            LedgerClient client = ledger.client();
            client.post("/api/v1/agents", LedgerClient.json(Map.of("agentId", "new-agent", "name", "new-agent",
                    "ownerId", "acme")));
            deny(client, "new-agent", 8);

            assertThat(client.post("/api/v1/anomalies/detect").json().path("items")).isEmpty();
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
