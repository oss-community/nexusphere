package com.nexusphere.ledger.e2e.cluster;

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
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterE2ETest {

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

    private StandaloneLedger instance(String id) {
        return StandaloneLedger.start(postgres, freePort(), keys, "--ledger.cluster.instance-id=" + id,
                "--ledger.cluster.heartbeat=500ms");
    }

    private static boolean leads(LedgerClient client) {
        return client.get("/api/v1/cluster").json().path("leader").asBoolean();
    }

    private static void waitUntil(BooleanSupplier condition) {
        long until = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static JsonNode record(LedgerClient client, int n) {
        return client.recordEvidence(Map.of("agentId", "payments-agent", "principalId", "alice",
                "action", "tools/call", "target", "bank/pay_invoice-" + n, "decision", "ALLOW",
                "outcome", "SUCCEEDED"));
    }

    @Test
    void oneInstanceLeadsBothWriteOneChainAndTheOtherTakesOver() {
        StandaloneLedger first = instance("ledger-a");
        try (StandaloneLedger second = instance("ledger-b")) {
            LedgerClient a = first.client();
            LedgerClient b = second.client();
            waitUntil(() -> leads(a));
            assertThat(leads(a)).isTrue();
            assertThat(leads(b)).isFalse();
            waitUntil(() -> a.get("/api/v1/cluster").json().path("members").size() == 2);
            List<String> members = new ArrayList<>();
            a.get("/api/v1/cluster").json().path("members").forEach(m ->
                    members.add(m.path("instanceId").asString() + ":" + m.path("leader").asBoolean()));
            assertThat(members).containsExactly("ledger-a:true", "ledger-b:false");
            assertThat(b.get("/actuator/prometheus").body()).contains("ledger_leader{application=\"nexusphere-ledger\"} 0.0");

            List<Long> sequences = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                sequences.add(record(i % 2 == 0 ? a : b, i).path("sequence").asLong());
            }
            assertThat(sequences).isSorted().doesNotHaveDuplicates();
            assertThat(sequences.getLast() - sequences.getFirst()).isEqualTo(9);

            first.close();
            waitUntil(() -> leads(b));
            assertThat(leads(b)).isTrue();
            assertThat(b.get("/actuator/prometheus").body()).contains("ledger_leader{application=\"nexusphere-ledger\"} 1.0");
            assertThat(b.post("/api/v1/checkpoints").status()).isEqualTo(200);
            assertThat(b.get("/api/v1/verification").json().path("valid").asBoolean()).isTrue();
            assertThat(b.withApiKey(null).get("/api/v1/cluster").status()).isEqualTo(401);
        } finally {
            first.close();
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
