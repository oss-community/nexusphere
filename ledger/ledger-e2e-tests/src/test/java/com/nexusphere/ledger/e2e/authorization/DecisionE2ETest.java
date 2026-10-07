package com.nexusphere.ledger.e2e.authorization;

import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionE2ETest extends LedgerE2ETestBase {

    @Test
    void aCoveredActionIsAllowedAndItsOutcomeIsRecorded() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("tools/call"), List.of("send_*")));
        String input = "b".repeat(64);

        LedgerClient.Response decided = agent.client().post("/api/v1/decisions", LedgerClient.json(Map.of(
                "principalId", "alice", "action", "tools/call", "target", "send_email", "inputHash", input,
                "correlationId", "conversation-9")));
        String decisionId = decided.json().path("decisionId").asString();
        JsonNode reported = agent.client().post("/api/v1/decisions/" + decisionId + "/outcome",
                LedgerClient.json(Map.of("outcome", "SUCCEEDED", "outputHash", "c".repeat(64)))).json();
        JsonNode decisionEvidence = ledger().get("/api/v1/evidence/" + decisionId).json();
        JsonNode outcomeEvidence = ledger().get("/api/v1/evidence/"
                + reported.path("evidence").path("id").asString()).json();
        JsonNode decision = agent.client().get("/api/v1/decisions/" + decisionId).json();

        assertThat(decided.status()).isEqualTo(201);
        assertThat(decided.json().path("decision").asString()).isEqualTo("ALLOW");
        assertThat(decided.json().path("reasonCode").asString()).isEqualTo("ALLOWED_BY_GRANT");
        assertThat(decided.json().path("grantId").asString()).isEqualTo(grantId);
        assertThat(decisionEvidence.path("outcome").asString()).isEqualTo("PENDING");
        assertThat(decisionEvidence.path("delegationId").asString()).isEqualTo(grantId);
        assertThat(decisionEvidence.path("inputHash").asString()).isEqualTo(input);
        assertThat(outcomeEvidence.path("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(outcomeEvidence.path("attributes").path("decisionId").asString()).isEqualTo(decisionId);
        assertThat(outcomeEvidence.path("correlationId").asString()).isEqualTo("conversation-9");
        assertThat(outcomeEvidence.path("inputHash").asString()).isEqualTo(input);
        assertThat(decision.path("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(ledger().get("/api/v1/grants/" + grantId).json().path("uses").asLong()).isEqualTo(1);
    }

    @Test
    void everyDenialIsRecordedWithItsReason() {
        RegisteredAgent agent = registerAgent("acme");
        LedgerClient client = agent.client();
        JsonNode noGrant = client.post("/api/v1/decisions", decision("bob", "tools/call", "search")).json();
        grant(grantBody("bob", agent.agentId(), List.of("tools/call"), List.of("search")));
        JsonNode notCovered = client.post("/api/v1/decisions", decision("bob", "tools/call", "delete_repo")).json();
        Map<String, Object> once = grantBody("carol", agent.agentId(), List.of("tools/call"), List.of("pay"));
        once.put("maxUses", 1);
        grant(once);
        client.post("/api/v1/decisions", decision("carol", "tools/call", "pay"));
        JsonNode exhausted = client.post("/api/v1/decisions", decision("carol", "tools/call", "pay")).json();
        Map<String, Object> later = grantBody("dave", agent.agentId(), List.of("*"), List.of("*"));
        later.put("notBefore", Instant.now().plus(30, ChronoUnit.MINUTES).toString());
        grant(later);
        JsonNode early = client.post("/api/v1/decisions", decision("dave", "tools/call", "search")).json();
        String revokedGrant = grant(grantBody("erin", agent.agentId(), List.of("*"), List.of("*")));
        ledger().post("/api/v1/grants/" + revokedGrant + "/revoke");
        JsonNode revoked = client.post("/api/v1/decisions", decision("erin", "tools/call", "search")).json();
        JsonNode deniedEvidence = ledger().get("/api/v1/evidence/" + notCovered.path("decisionId").asString()).json();

        assertThat(noGrant.path("decision").asString()).isEqualTo("DENY");
        assertThat(noGrant.path("reasonCode").asString()).isEqualTo("NO_ACTIVE_GRANT");
        assertThat(notCovered.path("reasonCode").asString()).isEqualTo("NOT_COVERED");
        assertThat(exhausted.path("reasonCode").asString()).isEqualTo("USES_EXHAUSTED");
        assertThat(early.path("reasonCode").asString()).isEqualTo("NOT_YET_VALID");
        assertThat(revoked.path("reasonCode").asString()).isEqualTo("NO_ACTIVE_GRANT");
        assertThat(deniedEvidence.path("decision").asString()).isEqualTo("DENY");
        assertThat(deniedEvidence.path("outcome").asString()).isEqualTo("DENIED");
        assertThat(deniedEvidence.path("reason").asString()).contains("delete_repo");
    }

    @Test
    void theOperatorCanAskForADecisionOnBehalfOfAnAgent() {
        RegisteredAgent agent = registerAgent("acme");
        grant(grantBody("frank", agent.agentId(), List.of("tools/call"), List.of("search")));

        JsonNode allowed = ledger().post("/api/v1/decisions", LedgerClient.json(Map.of("agentId", agent.agentId(),
                "principalId", "frank", "action", "tools/call", "target", "search"))).json();
        JsonNode unknown = ledger().post("/api/v1/decisions", LedgerClient.json(Map.of("agentId", unique("ghost"),
                "principalId", "frank", "action", "tools/call", "target", "search"))).json();

        assertThat(allowed.path("decision").asString()).isEqualTo("ALLOW");
        assertThat(unknown.path("decision").asString()).isEqualTo("DENY");
        assertThat(unknown.path("reasonCode").asString()).isEqualTo("AGENT_NOT_ACTIVE");
    }

    @Test
    void outcomesAreReportedOnceByTheDecidingAgentForAllowedActionsOnly() {
        RegisteredAgent agent = registerAgent("acme");
        RegisteredAgent other = registerAgent("acme");
        grant(grantBody("grace", agent.agentId(), List.of("tools/call"), List.of("search")));
        String allowed = agent.client().post("/api/v1/decisions", decision("grace", "tools/call", "search")).json()
                .path("decisionId").asString();
        String denied = agent.client().post("/api/v1/decisions", decision("grace", "tools/call", "pay")).json()
                .path("decisionId").asString();
        String failed = LedgerClient.json(Map.of("outcome", "FAILED"));

        LedgerClient.Response byOther = other.client().post("/api/v1/decisions/" + allowed + "/outcome", failed);
        LedgerClient.Response first = agent.client().post("/api/v1/decisions/" + allowed + "/outcome", failed);
        LedgerClient.Response second = agent.client().post("/api/v1/decisions/" + allowed + "/outcome", failed);
        LedgerClient.Response onDenied = agent.client().post("/api/v1/decisions/" + denied + "/outcome", failed);
        LedgerClient.Response pending = agent.client().post("/api/v1/decisions/" + allowed + "/outcome",
                LedgerClient.json(Map.of("outcome", "PENDING")));

        assertThat(byOther.status()).isEqualTo(404);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().path("outcome").asString()).isEqualTo("FAILED");
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.json().path("code").asString()).isEqualTo("OUTCOME_RECORDED");
        assertThat(onDenied.status()).isEqualTo(409);
        assertThat(onDenied.json().path("code").asString()).isEqualTo("DECISION_DENIED");
        assertThat(pending.status()).isEqualTo(400);
    }

    @Test
    void anAgentCannotAskForAnotherAgentsDecisionAndTheChainStaysValid() {
        RegisteredAgent agent = registerAgent("acme");
        RegisteredAgent other = registerAgent("acme");

        LedgerClient.Response response = agent.client().post("/api/v1/decisions", LedgerClient.json(Map.of(
                "agentId", other.agentId(), "principalId", "henry", "action", "tools/call")));
        LedgerClient.Response invalid = agent.client().post("/api/v1/decisions",
                LedgerClient.json(Map.of("action", "tools/call")));

        assertThat(response.status()).isEqualTo(403);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(ledger().get("/api/v1/verification").json().path("valid").asBoolean()).isTrue();
    }
}
