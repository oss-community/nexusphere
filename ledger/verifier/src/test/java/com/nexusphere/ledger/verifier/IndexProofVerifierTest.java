package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.IndexRoot;
import com.nexusphere.ledger.chain.SigningKeys;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.security.KeyPair;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexProofVerifierTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final KeyPair KEYS = SigningKeys.generate();
    private static final String KEY_ID = SigningKeys.keyIdOf(KEYS.getPublic());
    private static final String PINNED = SigningKeys.encode(KEYS.getPublic());

    private static ObjectNode proof(Map<String, Object> term) {
        return JSON.valueToTree(ConformanceVectorsTest.indexProof(ConformanceVectorsTest.entries(),
                IndexRoot.term(term), 3, KEYS.getPrivate(), KEY_ID, KEYS.getPublic()));
    }

    @Test
    void aProofShowsHowOftenATermOccursOrThatItNeverDid() {
        IndexProofVerifier.IndexCheck present = IndexProofVerifier.verify(
                proof(Map.of("type", "agent", "agentId", "invoice-agent")), PINNED, null);
        IndexProofVerifier.IndexCheck absent = IndexProofVerifier.verify(
                proof(Map.of("type", "grant", "delegationId", "grant-1")), PINNED, null);

        assertThat(present.problems()).isEmpty();
        assertThat(present.present()).isTrue();
        assertThat(present.count()).isEqualTo(2);
        assertThat(present.lastSequence()).isEqualTo(2);
        assertThat(absent.problems()).isEmpty();
        assertThat(absent.present()).isFalse();
        assertThat(absent.sequence()).isEqualTo(3);
    }

    @Test
    void aChangedLeafOrTermDoesNotLeadToTheSignedRoot() {
        ObjectNode fewer = proof(Map.of("type", "agent", "agentId", "invoice-agent"));
        ((ObjectNode) fewer.path("leaf")).put("count", 1);
        ObjectNode hidden = proof(Map.of("type", "agent", "agentId", "invoice-agent"));
        hidden.putNull("leaf");
        ObjectNode other = proof(Map.of("type", "agent", "agentId", "invoice-agent"));
        ((ObjectNode) other.path("term")).put("agentId", "sales-agent");

        assertThat(IndexProofVerifier.verify(fewer, PINNED, null).problems())
                .containsExactly("the proof does not lead to the signed root");
        assertThat(IndexProofVerifier.verify(hidden, PINNED, null).problems())
                .containsExactly("the proof does not lead to the signed root");
        assertThat(IndexProofVerifier.verify(other, PINNED, null).problems())
                .contains("the key does not belong to the term");
    }

    @Test
    void theIndexMustBeSignedByATrustedKeyAndMatchThePackage() {
        ObjectNode proof = proof(Map.of("type", "input", "inputHash", Hashes.GENESIS));

        assertThat(IndexProofVerifier.verify(proof, SigningKeys.encode(SigningKeys.generate().getPublic()), null)
                .problems()).anyMatch(p -> p.contains("which the proof does not trust"));
        ObjectNode pkg = JSON.createObjectNode();
        pkg.putObject("checkpoint").put("sequence", 2).put("headHash", Hashes.GENESIS);
        assertThat(IndexProofVerifier.verify(proof, PINNED, null, pkg).problems())
                .containsExactly("the index is for another checkpoint than the package");
        ((ObjectNode) proof.path("index")).put("root", Hashes.GENESIS);
        assertThat(IndexProofVerifier.verify(proof, PINNED, null).problems())
                .containsExactly("the signature of the index is not valid", "the proof does not lead to the signed root");
        proof.put("format", "other");
        assertThat(IndexProofVerifier.verify(proof, PINNED, null).problems())
                .containsExactly("unknown index proof format other");
    }

    @Test
    void anUnknownTermIsRefused() {
        ObjectNode proof = proof(Map.of("type", "agent", "agentId", "invoice-agent"));
        proof.putObject("term").put("type", "principal").put("principalId", "alice");

        assertThat(IndexProofVerifier.verify(proof, PINNED, null).problems())
                .containsExactly("the term is not valid: type must be one of agent, action, input or grant");
    }
}
