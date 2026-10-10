package com.nexusphere.ledger.e2e.agent;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import com.nexusphere.ledger.mandate.KeyBinding;
import com.nexusphere.ledger.mandate.MandateProblem;
import com.nexusphere.ledger.mandate.SdJwt;
import com.nexusphere.ledger.mandate.TrackRecordCheck;
import com.nexusphere.ledger.mandate.TrackRecordVerifier;
import com.nexusphere.ledger.verifier.IndexProofVerifier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TrackRecordE2ETest extends LedgerE2ETestBase {

    private static final String AUDIENCE = "https://supplier.test";

    private static TrackRecordVerifier.Builder verifier() {
        return TrackRecordVerifier.builder().trustIssuer(ISSUER).audience(AUDIENCE).cacheTtl(Duration.ZERO);
    }

    private static void act(RegisteredAgent agent, String principal, String outcome) {
        agent.client().recordEvidence(Map.of("agentId", agent.agentId(), "principalId", principal,
                "action", "tools/call", "outcome", outcome));
    }

    private static JsonNode issue(LedgerClient client, String agentId, Map<String, Object> body) {
        LedgerClient.Response response = client.post("/api/v1/agents/" + agentId + "/track-record",
                LedgerClient.json(body));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    @Test
    void anotherOrganizationChecksTheTrackRecordAgainstTheSignedIndex() {
        RegisteredAgent agent = registerAgent("acme");
        act(agent, "alice", "SUCCEEDED");
        act(agent, "bob", "SUCCEEDED");
        act(agent, "alice", "FAILED");
        long checkpoint = ledger().post("/api/v1/checkpoints").json().path("sequence").asLong();
        act(agent, "alice", "SUCCEEDED");

        JsonNode issued = issue(agent.client(), agent.agentId(), Map.of("audience", AUDIENCE, "validFor", "P7D"));
        String credential = issued.path("credential").asString();
        TrackRecordCheck check = verifier().build().verify(credential);
        JsonNode proof = ledger().post("/api/v1/index/proofs", LedgerClient.json(Map.of("term",
                Map.of("type", "agent", "agentId", agent.agentId()), "sequence", checkpoint))).json();
        IndexProofVerifier.IndexCheck index = IndexProofVerifier.verify(proof, null, null);

        assertThat(check.problems()).isEmpty();
        assertThat(issued.path("checkpoint").asLong()).isEqualTo(checkpoint);
        assertThat(check.claims().checkpointSequence()).isEqualTo(checkpoint);
        assertThat(check.claims().total()).isEqualTo(4);
        assertThat(check.claims().outcomes()).containsEntry("SUCCEEDED", 3L).containsEntry("FAILED", 1L);
        assertThat(check.claims().principals()).isEqualTo(3);
        assertThat(check.claims().actions()).containsEntry("tools/call", 3L).containsEntry("agent/register", 1L);
        assertThat(check.claims().expiresAt()).isEqualTo(Instant.parse(issued.path("expiresAt").asString()));
        assertThat(Duration.between(check.claims().issuedAt(), check.claims().expiresAt())).isEqualTo(Duration.ofDays(7));
        assertThat(index.problems()).isEmpty();
        assertThat(IndexProofVerifier.matchTrackRecord(check.claims(), proof, index)).isEmpty();
        JsonNode recorded = ledger().get("/api/v1/evidence?agentId=" + agent.agentId()).json().path("items");
        assertThat(recorded.findValuesAsString("action")).contains("track-record/issue");
        assertThat(recorded.findValuesAsString("inputHash"))
                .contains(Hashes.sha256(credential.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void theAgentWithholdsCountsAndProvesItHoldsTheRecord() {
        RegisteredAgent agent = registerAgent("acme");
        KeyPair keys = SigningKeys.generate();
        agent.client().put("/api/v1/agents/" + agent.agentId() + "/signing-key",
                LedgerClient.json(Map.of("publicKey", SigningKeys.encode(keys.getPublic()))));
        act(agent, "alice", "SUCCEEDED");
        ledger().post("/api/v1/checkpoints");
        String credential = issue(agent.client(), agent.agentId(), Map.of("audience", AUDIENCE))
                .path("credential").asString();
        String nonce = "ab".repeat(32);

        String withheld = SdJwt.parse(credential).present(Set.of("outcomes"));
        String presented = KeyBinding.present(withheld, keys.getPrivate(), AUDIENCE, nonce, Instant.now());
        TrackRecordCheck check = verifier().build().verifyBound(presented, nonce);
        TrackRecordCheck bare = verifier().build().verify(credential);

        assertThat(check.problems()).isEmpty();
        assertThat(check.claims().holderKey()).isEqualTo(keys.getPublic());
        assertThat(check.claims().outcomes()).containsEntry("SUCCEEDED", 3L);
        assertThat(check.claims().principals()).isNull();
        assertThat(check.claims().actions()).isNull();
        assertThat(bare.has(MandateProblem.KEY_BINDING_MISSING)).isTrue();
    }

    @Test
    void onlyTheAgentOrTheOperatorAsksAndTheValidityIsBounded() {
        RegisteredAgent agent = registerAgent("acme");
        RegisteredAgent other = registerAgent("acme");
        ledger().post("/api/v1/checkpoints");
        String path = "/api/v1/agents/" + agent.agentId() + "/track-record";

        assertThat(other.client().post(path, "{}").status()).isEqualTo(404);
        LedgerClient.Response tooLong = ledger().post(path, LedgerClient.json(Map.of("validFor", "P400D")));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.json().path("details").path("fields").path("validFor").asString())
                .isEqualTo("must be an ISO-8601 duration between PT1M and P365D");
        JsonNode byOperator = issue(ledger(), agent.agentId(), Map.of());
        TrackRecordCheck check = TrackRecordVerifier.builder().trustIssuer(ISSUER).cacheTtl(Duration.ZERO).build()
                .verify(byOperator.path("credential").asString());
        assertThat(check.problems()).isEmpty();
        assertThat(check.claims().audience()).isNull();
        assertThat(Duration.between(check.claims().issuedAt(), check.claims().expiresAt()))
                .isEqualTo(Duration.ofDays(30));
    }
}
