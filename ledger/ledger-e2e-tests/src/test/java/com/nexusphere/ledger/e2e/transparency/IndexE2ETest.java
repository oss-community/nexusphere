package com.nexusphere.ledger.e2e.transparency;

import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import com.nexusphere.ledger.verifier.IndexProofVerifier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexE2ETest extends LedgerE2ETestBase {

    private String activeKey() {
        for (JsonNode key : ledger().get("/api/v1/keys").json()) {
            if ("ACTIVE".equals(key.path("status").asString())) {
                return key.path("publicKey").asString();
            }
        }
        throw new AssertionError("no active key");
    }

    private JsonNode proof(Map<String, Object> term, Long sequence) {
        Map<String, Object> body = sequence == null ? Map.of("term", term) : Map.of("term", term, "sequence", sequence);
        LedgerClient.Response response = ledger().post("/api/v1/index/proofs", LedgerClient.json(body));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    @Test
    void theLedgerProvesThatAGrantWasUsedAndThatAnotherNeverWas() {
        RegisteredAgent agent = registerAgent("acme");
        String used = grant(grantBody("alice", agent.agentId(), List.of("tools/call"), List.of("send_*")));
        String unused = grant(grantBody("alice", agent.agentId(), List.of("tools/call"), List.of("delete_*")));
        String input = "d".repeat(64);
        String decisionId = agent.client().post("/api/v1/decisions", LedgerClient.json(Map.of("principalId", "alice",
                "action", "tools/call", "target", "send_email", "inputHash", input))).json()
                .path("decisionId").asString();
        long outcome = agent.client().post("/api/v1/decisions/" + decisionId + "/outcome",
                LedgerClient.json(Map.of("outcome", "SUCCEEDED"))).json().path("evidence").path("sequence").asLong();
        long checkpoint = ledger().post("/api/v1/checkpoints").json().path("sequence").asLong();
        String key = activeKey();

        JsonNode usedProof = proof(Map.of("type", "grant", "delegationId", used), null);
        JsonNode unusedProof = proof(Map.of("type", "grant", "delegationId", unused), null);
        JsonNode inputProof = proof(Map.of("type", "input", "inputHash", input), checkpoint);
        IndexProofVerifier.IndexCheck usedCheck = IndexProofVerifier.verify(usedProof, key, null);
        IndexProofVerifier.IndexCheck unusedCheck = IndexProofVerifier.verify(unusedProof, key, null);
        IndexProofVerifier.IndexCheck inputCheck = IndexProofVerifier.verify(inputProof, key, null);

        assertThat(usedCheck.problems()).isEmpty();
        assertThat(usedCheck.present()).isTrue();
        assertThat(usedCheck.lastSequence()).isEqualTo(outcome);
        assertThat(unusedCheck.problems()).isEmpty();
        assertThat(unusedCheck.present()).isFalse();
        assertThat(inputCheck.problems()).isEmpty();
        assertThat(inputCheck.count()).isEqualTo(2);
        assertThat(inputCheck.sequence()).isEqualTo(checkpoint);
        assertThat(usedProof.path("index").path("root")).isEqualTo(unusedProof.path("index").path("root"));
    }

    @Test
    void anEarlierCheckpointKeepsItsSignedIndex() {
        RegisteredAgent agent = registerAgent("acme");
        agent.client().recordEvidence(Map.of("agentId", agent.agentId(), "principalId", "alice",
                "action", "tools/call", "outcome", "SUCCEEDED"));
        long first = ledger().post("/api/v1/checkpoints").json().path("sequence").asLong();
        Map<String, Object> term = Map.of("type", "agent", "agentId", agent.agentId());
        JsonNode before = proof(term, first);
        agent.client().recordEvidence(Map.of("agentId", agent.agentId(), "principalId", "alice",
                "action", "tools/call", "outcome", "SUCCEEDED"));
        long second = ledger().post("/api/v1/checkpoints").json().path("sequence").asLong();

        JsonNode after = proof(term, second);
        JsonNode again = proof(term, first);

        assertThat(IndexProofVerifier.verify(after, activeKey(), null).count())
                .isEqualTo(IndexProofVerifier.verify(before, activeKey(), null).count() + 1);
        assertThat(again.path("index")).isEqualTo(before.path("index"));
    }

    @Test
    void onlyTheOperatorAsksForKnownTermsAtKnownCheckpoints() {
        RegisteredAgent agent = registerAgent("acme");
        agent.client().recordEvidence(Map.of("agentId", agent.agentId(), "principalId", "alice",
                "action", "tools/call", "outcome", "SUCCEEDED"));
        ledger().post("/api/v1/checkpoints");
        String body = LedgerClient.json(Map.of("term", Map.of("type", "agent", "agentId", agent.agentId())));

        assertThat(agent.client().post("/api/v1/index/proofs", body).status()).isEqualTo(403);
        LedgerClient.Response unknown = ledger().post("/api/v1/index/proofs", LedgerClient.json(Map.of(
                "term", Map.of("type", "principal", "principalId", "alice"))));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.json().path("details").path("fields").path("term").asString())
                .isEqualTo("type must be one of agent, action, input or grant");
        assertThat(ledger().post("/api/v1/index/proofs", LedgerClient.json(Map.of(
                "term", Map.of("type", "agent", "agentId", agent.agentId()), "sequence", 999_999))).status())
                .isEqualTo(404);
    }
}
