package com.nexusphere.ledger.e2e.authorization;

import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentE2ETest extends LedgerE2ETestBase {

    @Test
    void aRegisteredAgentGetsItsOwnKeyOnce() {
        String agentId = unique("agent");
        LedgerClient.Response created = ledger().post("/api/v1/agents",
                LedgerClient.json(Map.of("agentId", agentId, "name", "Invoice agent", "ownerId", "acme")));
        String apiKey = created.json().path("apiKey").asString();

        JsonNode read = ledger().get("/api/v1/agents/" + agentId).json();
        JsonNode self = ledger().withApiKey(apiKey).get("/api/v1/agents/" + agentId).json();

        assertThat(created.status()).isEqualTo(201);
        assertThat(apiKey).startsWith("nxl_");
        assertThat(created.json().path("keyPrefix").asString()).isEqualTo(apiKey.substring(0, 10));
        assertThat(read.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(read.has("apiKey")).isFalse();
        assertThat(self.path("agentId").asString()).isEqualTo(agentId);
    }

    @Test
    void registeringTheSameAgentTwiceIsAConflict() {
        RegisteredAgent agent = registerAgent("acme");

        LedgerClient.Response again = ledger().post("/api/v1/agents",
                LedgerClient.json(Map.of("agentId", agent.agentId(), "name", "Copy", "ownerId", "acme")));
        LedgerClient.Response invalid = ledger().post("/api/v1/agents",
                LedgerClient.json(Map.of("agentId", "has spaces", "name", "x")));

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.json().path("code").asString()).isEqualTo("AGENT_EXISTS");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.json().path("details").path("fields").has("agentId")).isTrue();
        assertThat(invalid.json().path("details").path("fields").has("ownerId")).isTrue();
    }

    @Test
    void aDisabledAgentOrAnOldKeyIsRejected() {
        RegisteredAgent rotated = registerAgent("acme");
        RegisteredAgent disabled = registerAgent("acme");

        String newKey = ledger().post("/api/v1/agents/" + rotated.agentId() + "/key").json()
                .path("apiKey").asString();
        ledger().post("/api/v1/agents/" + disabled.agentId() + "/disable");

        assertThat(rotated.client().get("/api/v1/ledger/head").status()).isEqualTo(401);
        assertThat(ledger().withApiKey(newKey).get("/api/v1/ledger/head").status()).isEqualTo(200);
        assertThat(disabled.client().get("/api/v1/ledger/head").status()).isEqualTo(401);
        assertThat(ledger().get("/api/v1/agents/" + disabled.agentId()).json().path("status").asString())
                .isEqualTo("DISABLED");
    }

    @Test
    void agentLifecycleIsRecordedAsEvidence() {
        RegisteredAgent agent = registerAgent("acme");
        ledger().post("/api/v1/agents/" + agent.agentId() + "/disable");

        JsonNode items = ledger().get("/api/v1/evidence?agentId=" + agent.agentId()).json().path("items");

        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).path("action").asString()).isEqualTo("agent/register");
        assertThat(items.get(0).path("principalId").asString()).isEqualTo("acme");
        assertThat(items.get(1).path("action").asString()).isEqualTo("agent/disable");
    }

    @Test
    void anAgentCannotDoWhatOnlyTheOperatorMay() {
        RegisteredAgent agent = registerAgent("acme");
        RegisteredAgent other = registerAgent("acme");
        LedgerClient client = agent.client();

        assertThat(client.post("/api/v1/agents", LedgerClient.json(Map.of("agentId", unique("x"), "name", "x",
                "ownerId", "x"))).status()).isEqualTo(403);
        assertThat(client.post("/api/v1/grants", LedgerClient.json(grantBody("alice", agent.agentId(),
                List.of("*"), List.of("*")))).status()).isEqualTo(403);
        assertThat(client.post("/api/v1/checkpoints").status()).isEqualTo(403);
        assertThat(client.get("/api/v1/verification").status()).isEqualTo(403);
        assertThat(client.get("/api/v1/agents/" + other.agentId()).status()).isEqualTo(404);
        assertThat(client.get("/api/v1/keys").status()).isEqualTo(200);
    }

    @Test
    void anAgentRecordsAndReadsOnlyItsOwnEvidence() {
        RegisteredAgent agent = registerAgent("acme");
        RegisteredAgent other = registerAgent("acme");
        LedgerClient client = agent.client();

        LedgerClient.Response own = client.post("/api/v1/evidence",
                LedgerClient.json(toolCall(agent.agentId(), "alice", "search")));
        LedgerClient.Response foreign = client.post("/api/v1/evidence",
                LedgerClient.json(toolCall(other.agentId(), "alice", "search")));
        JsonNode otherEvidence = other.client().recordEvidence(toolCall(other.agentId(), "bob", "search"));
        JsonNode listed = client.get("/api/v1/evidence").json().path("items");

        assertThat(own.status()).isEqualTo(201);
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(client.get("/api/v1/evidence/" + otherEvidence.path("id").asString()).status()).isEqualTo(404);
        assertThat(client.get("/api/v1/evidence?agentId=" + other.agentId()).status()).isEqualTo(403);
        listed.forEach(item -> assertThat(item.path("agentId").asString()).isEqualTo(agent.agentId()));
    }
}
