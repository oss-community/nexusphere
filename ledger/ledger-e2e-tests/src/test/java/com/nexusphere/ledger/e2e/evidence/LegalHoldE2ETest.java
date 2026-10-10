package com.nexusphere.ledger.e2e.evidence;

import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegalHoldE2ETest extends LedgerE2ETestBase {

    private LedgerClient.Response place(String principal, String reason) {
        return ledger().post("/api/v1/legal-holds", LedgerClient.json(Map.of("principalId", principal,
                "reason", reason)));
    }

    private LedgerClient.Response release(String id) {
        return ledger().post("/api/v1/legal-holds/" + id + "/release",
                LedgerClient.json(Map.of("reason", "dispute settled")));
    }

    @Test
    void aLegalHoldKeepsThePersonalDataUntilItIsReleased() {
        String principal = unique("person");
        JsonNode recorded = ledger().recordEvidence(toolCall(unique("agent"), principal, "read_file"));
        LedgerClient.Response placed = place(principal, "dispute 42");
        assertThat(placed.status()).isEqualTo(201);
        String holdId = placed.json().path("id").asString();

        JsonNode erasure = ledger().post("/api/v1/principals/" + principal + "/erasure",
                LedgerClient.json(Map.of("reason", "request of the principal"))).json();

        assertThat(erasure.path("legalHold").asBoolean()).isTrue();
        assertThat(erasure.path("completed").asBoolean()).isFalse();
        assertThat(erasure.path("erasedEntries").asLong()).isZero();
        assertThat(erasure.path("retainedEntries").asLong()).isEqualTo(1);
        assertThat(ledger().post("/api/v1/retention/sweep").json().path("erasures").findValuesAsString("principalRef"))
                .doesNotContain(erasure.path("principalRef").asString());
        assertThat(ledger().get("/api/v1/evidence/" + recorded.path("id").asString()).json().path("principalId")
                .asString()).isEqualTo(principal);
        assertThat(ledger().get("/api/v1/legal-holds?active=true").json().path("items").findValuesAsString("id"))
                .contains(holdId);

        assertThat(release(holdId).status()).isEqualTo(200);
        assertThat(release(holdId).json().path("code").asString()).isEqualTo("HOLD_RELEASED");
        JsonNode sweep = ledger().post("/api/v1/retention/sweep").json();

        assertThat(sweep.path("erasures").findValuesAsString("principalRef"))
                .contains(erasure.path("principalRef").asString());
        assertThat(ledger().get("/api/v1/evidence/" + recorded.path("id").asString()).json().path("erased")
                .asBoolean()).isTrue();
        assertThat(ledger().get("/api/v1/legal-holds/" + holdId).json().path("releaseReason").asString())
                .isEqualTo("dispute settled");
        assertThat(ledger().get("/api/v1/legal-holds?active=true").json().path("items").findValuesAsString("id"))
                .doesNotContain(holdId);
    }

    @Test
    void aLegalHoldNeedsAKnownPrincipalAReasonAndTheOperator() {
        assertThat(place(unique("nobody"), "dispute").status()).isEqualTo(404);
        String principal = unique("person");
        RegisteredAgent agent = registerAgent("acme");
        ledger().recordEvidence(toolCall(agent.agentId(), principal, "read_file"));
        assertThat(place(principal, " ").status()).isEqualTo(400);
        assertThat(agent.client().post("/api/v1/legal-holds", LedgerClient.json(Map.of("principalId", principal,
                "reason", "dispute"))).status()).isEqualTo(403);
        assertThat(agent.client().post("/api/v1/retention/sweep").status()).isEqualTo(403);
    }
}
