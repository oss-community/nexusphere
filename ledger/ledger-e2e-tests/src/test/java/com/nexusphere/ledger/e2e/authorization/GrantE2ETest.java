package com.nexusphere.ledger.e2e.authorization;

import com.nexusphere.ledger.chain.GrantTerms;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GrantE2ETest extends LedgerE2ETestBase {

    @Test
    void aGrantIsCreatedAndItsTermsCanBeRecomputedFromTheEvidence() {
        RegisteredAgent agent = registerAgent("acme");
        Map<String, Object> body = grantBody("alice", agent.agentId(), List.of("tools/call"),
                List.of("send_email", "read_*"));
        body.put("maxUses", 10);
        body.put("reason", "Monthly invoices");

        LedgerClient.Response created = ledger().post("/api/v1/grants", LedgerClient.json(body));
        JsonNode grant = created.json();
        JsonNode evidence = ledger().get("/api/v1/evidence?agentId=" + agent.agentId()).json().path("items").get(1);
        GrantTerms terms = new GrantTerms(UUID.fromString(grant.path("id").asString()),
                grant.path("principalId").asString(), grant.path("agentId").asString(), strings(grant.path("actions")),
                strings(grant.path("targets")), null, Instant.parse(grant.path("expiresAt").asString()),
                grant.path("maxUses").asLong(), Instant.parse(grant.path("createdAt").asString()));

        assertThat(created.status()).isEqualTo(201);
        assertThat(grant.path("format").asString()).isEqualTo("nexusphere-ledger/grant/v1");
        assertThat(grant.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(grant.path("uses").asLong()).isZero();
        assertThat(terms.hash()).isEqualTo(grant.path("termsHash").asString());
        assertThat(evidence.path("action").asString()).isEqualTo("grant/create");
        assertThat(evidence.path("principalId").asString()).isEqualTo("alice");
        assertThat(evidence.path("delegationId").asString()).isEqualTo(grant.path("id").asString());
        assertThat(evidence.path("inputHash").asString()).isEqualTo(terms.hash());
        assertThat(evidence.path("reason").asString()).isEqualTo("Monthly invoices");
        assertThat(evidence.path("attributes").path("maxUses").asString()).isEqualTo("10");
    }

    @Test
    void aRevokedGrantIsRecordedAndShownAsRevoked() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("*"), List.of("*")));

        JsonNode revoked = ledger().post("/api/v1/grants/" + grantId + "/revoke",
                LedgerClient.json(Map.of("reason", "Contract ended"))).json();
        JsonNode again = ledger().post("/api/v1/grants/" + grantId + "/revoke").json();
        JsonNode last = ledger().get("/api/v1/evidence?agentId=" + agent.agentId()).json().path("items").get(2);

        assertThat(revoked.path("status").asString()).isEqualTo("REVOKED");
        assertThat(revoked.path("revokeReason").asString()).isEqualTo("Contract ended");
        assertThat(again.path("revokedAt").asString()).isEqualTo(revoked.path("revokedAt").asString());
        assertThat(last.path("action").asString()).isEqualTo("grant/revoke");
        assertThat(last.path("reason").asString()).isEqualTo("Contract ended");
    }

    @Test
    void invalidGrantsAreRejectedWithFieldErrors() {
        RegisteredAgent agent = registerAgent("acme");
        Map<String, Object> body = grantBody("alice", agent.agentId(), List.of("tools/*/x"), List.of());
        body.put("expiresAt", "2020-01-01T00:00:00Z");
        body.put("maxUses", 0);

        LedgerClient.Response response = ledger().post("/api/v1/grants", LedgerClient.json(body));
        JsonNode fields = response.json().path("details").path("fields");

        assertThat(response.status()).isEqualTo(400);
        assertThat(fields.has("actions[0]")).isTrue();
        assertThat(fields.has("targets")).isTrue();
        assertThat(fields.has("expiresAt")).isTrue();
        assertThat(fields.has("maxUses")).isTrue();
    }

    @Test
    void grantsNeedAnActiveAgent() {
        RegisteredAgent agent = registerAgent("acme");
        ledger().post("/api/v1/agents/" + agent.agentId() + "/disable");

        LedgerClient.Response disabled = ledger().post("/api/v1/grants",
                LedgerClient.json(grantBody("alice", agent.agentId(), List.of("*"), List.of("*"))));
        LedgerClient.Response unknown = ledger().post("/api/v1/grants",
                LedgerClient.json(grantBody("alice", unique("ghost"), List.of("*"), List.of("*"))));

        assertThat(disabled.status()).isEqualTo(409);
        assertThat(disabled.json().path("code").asString()).isEqualTo("AGENT_NOT_ACTIVE");
        assertThat(unknown.status()).isEqualTo(404);
    }

    @Test
    void anAgentSeesOnlyItsOwnGrants() {
        RegisteredAgent agent = registerAgent("acme");
        RegisteredAgent other = registerAgent("acme");
        String own = grant(grantBody("alice", agent.agentId(), List.of("*"), List.of("*")));
        String foreign = grant(grantBody("alice", other.agentId(), List.of("*"), List.of("*")));

        JsonNode listed = agent.client().get("/api/v1/grants").json().path("items");

        assertThat(listed.size()).isEqualTo(1);
        assertThat(listed.get(0).path("id").asString()).isEqualTo(own);
        assertThat(agent.client().get("/api/v1/grants/" + foreign).status()).isEqualTo(404);
        assertThat(ledger().get("/api/v1/grants?principalId=alice&agentId=" + other.agentId()).json()
                .path("items").size()).isEqualTo(1);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }
}
