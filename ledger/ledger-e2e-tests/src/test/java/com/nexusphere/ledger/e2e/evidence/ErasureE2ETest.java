package com.nexusphere.ledger.e2e.evidence;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ErasureE2ETest extends LedgerE2ETestBase {

    private static LedgerClient.Response erase(LedgerClient client, String principal, String reason) {
        return client.post("/api/v1/principals/" + principal + "/erasure", LedgerClient.json(Map.of("reason", reason)));
    }

    private static PackageReport verify(LedgerClient client, String agent) {
        assertThat(client.post("/api/v1/checkpoints").status()).isEqualTo(200);
        JsonNode pkg = client.post("/api/v1/packages", LedgerClient.json(Map.of("agentId", agent))).json();
        String key = client.get("/api/v1/keys").json().get(0).path("publicKey").asString();
        return PackageVerifier.verify(pkg, key);
    }

    @Test
    void erasingAPrincipalRemovesItsPersonalDataAndKeepsTheChain() {
        String agent = unique("agent");
        String principal = unique("person");
        Map<String, Object> call = toolCall(agent, principal, "read_file");
        call.put("reason", "asked by " + principal);
        call.put("correlationId", "session-" + principal);
        JsonNode first = ledger().recordEvidence(call);
        JsonNode second = ledger().recordEvidence(toolCall(agent, principal, "send_email"));
        assertThat(first.path("erased").asBoolean()).isFalse();
        assertThat(first.path("salts").size()).isEqualTo(6);
        assertThat(LedgerClient.toEntry(first).computeHash()).isEqualTo(first.path("hash").asString());

        LedgerClient.Response response = erase(ledger(), principal, "request of the principal");

        assertThat(response.status()).isEqualTo(200);
        JsonNode erasure = response.json();
        assertThat(erasure.path("erasedEntries").asLong()).isEqualTo(2);
        assertThat(erasure.path("retainedEntries").asLong()).isZero();
        assertThat(erasure.path("completed").asBoolean()).isTrue();
        for (JsonNode before : List.of(first, second)) {
            JsonNode after = ledger().get("/api/v1/evidence/" + before.path("id").asString()).json();
            assertThat(after.path("erased").asBoolean()).isTrue();
            assertThat(after.path("principalId").isNull()).isTrue();
            assertThat(after.path("target").isNull()).isTrue();
            assertThat(after.path("salts").isNull()).isTrue();
            assertThat(after.path("attributes").isEmpty()).isTrue();
            assertThat(after.path("commitments")).isEqualTo(before.path("commitments"));
            assertThat(after.path("hash").asString()).isEqualTo(before.path("hash").asString());
            assertThat(LedgerClient.toEntry(after).computeHash()).isEqualTo(before.path("hash").asString());
            assertThat(after.toString()).doesNotContain(principal);
        }
        assertThat(ledger().get("/api/v1/evidence?principalId=" + principal).json().path("items").isEmpty())
                .isTrue();
        JsonNode recorded = ledger().get("/api/v1/evidence/" + erasure.path("evidenceId").asString()).json();
        assertThat(recorded.path("action").asString()).isEqualTo("principal/erase");
        assertThat(recorded.path("target").asString())
                .isEqualTo("principal:" + erasure.path("principalRef").asString());
        assertThat(recorded.path("attributes").path("erasedEntries").asString()).isEqualTo("2");
        assertThat(recorded.toString()).doesNotContain(principal);

        PackageReport report = verify(ledger(), agent);
        assertThat(report.valid()).as(report.problems().toString()).isTrue();
        assertThat(report.disclosedEntries()).isEqualTo(2);
        assertThat(erase(ledger(), principal, "again").status()).isEqualTo(404);
    }

    @Test
    void openGrantsBlockTheErasureAndAreThenPseudonymized() {
        RegisteredAgent agent = registerAgent("acme");
        String principal = unique("person");
        String grantId = grant(grantBody(principal, agent.agentId(), List.of("tools/call"), List.of("read_*")));
        ledger().recordEvidence(toolCall(agent.agentId(), principal, "read_file"));

        LedgerClient.Response blocked = erase(ledger(), principal, "request of the principal");

        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.json().path("code").asString()).isEqualTo("GRANTS_OPEN");
        assertThat(ledger().post("/api/v1/grants/" + grantId + "/revoke", "{}").status()).isEqualTo(200);
        JsonNode erasure = erase(ledger(), principal, "request of the principal").json();
        assertThat(erasure.path("completed").asBoolean()).isTrue();
        assertThat(ledger().get("/api/v1/grants/" + grantId).json().path("principalId").asString())
                .isEqualTo("erased:" + erasure.path("principalRef").asString());
    }

    @Test
    void theRetentionOfTheProfilesKeepsRecentEntries() {
        PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
        try (StandaloneLedger standalone = StandaloneLedger.start(postgres, freePort(), SigningKeys.generate(),
                "--ledger.compliance.profiles=us")) {
            LedgerClient client = standalone.client();
            String agent = unique("agent");
            String principal = unique("person");
            Map<String, Object> old = toolCall(agent, principal, "read_file");
            old.put("occurredAt", "2019-01-01T00:00:00Z");
            JsonNode expired = client.recordEvidence(old);
            JsonNode recent = client.recordEvidence(toolCall(agent, principal, "read_file"));

            JsonNode erasure = erase(client, principal, "request of the principal").json();

            assertThat(erasure.path("erasedEntries").asLong()).isEqualTo(1);
            assertThat(erasure.path("retainedEntries").asLong()).isEqualTo(1);
            assertThat(erasure.path("completed").asBoolean()).isFalse();
            Instant occurred = Instant.parse(recent.path("occurredAt").asString());
            assertThat(Instant.parse(erasure.path("retainedUntil").asString()))
                    .isEqualTo(occurred.atOffset(ZoneOffset.UTC).plusYears(6).toInstant());
            assertThat(client.get("/api/v1/evidence/" + expired.path("id").asString()).json().path("erased")
                    .asBoolean()).isTrue();
            assertThat(client.get("/api/v1/evidence/" + recent.path("id").asString()).json().path("principalId")
                    .asString()).isEqualTo(principal);
            PackageReport report = verify(client, agent);
            assertThat(report.valid()).as(report.problems().toString()).isTrue();
        } finally {
            postgres.stop();
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
