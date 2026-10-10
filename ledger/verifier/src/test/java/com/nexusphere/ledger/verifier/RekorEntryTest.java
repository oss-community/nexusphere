package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.SigningKeys;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RekorEntryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode VECTOR = JSON.readTree(Path.of("../conformance/vectors/rekor.json").toFile());
    private static final PublicKey LEDGER = SigningKeys.decodePublic(JSON.readTree(
            Path.of("../conformance/vectors/keys.json").toFile()).path("ledgerPublicKey").asString());
    private static final byte[] SIGNED = VECTOR.path("signedBytes").asString().getBytes(StandardCharsets.UTF_8);
    private static final NoteKey REKOR = NoteKey.parse(VECTOR.path("rekorVerifierKey").asString());

    private static ObjectNode entry() {
        return (ObjectNode) VECTOR.path("entry").deepCopy();
    }

    @Test
    void anEntryFromARealRekorLogVerifies() {
        RekorEntry.Verification result = RekorEntry.verify(entry(), SIGNED, LEDGER, List.of(REKOR));

        assertThat(result.problem()).isNull();
        assertThat(result.valid()).isTrue();
        assertThat(result.pinned()).isTrue();
        assertThat(result.origin()).isEqualTo("rekor.local");
        assertThat(result.logIndex()).isEqualTo(VECTOR.path("entry").path("logIndex").asLong());
        assertThat(RekorEntry.verify(entry(), SIGNED, LEDGER, List.of()).pinned()).isFalse();
    }

    @Test
    void theEntrySignatureIsTheLedgerKeysEd25519phSignature() {
        byte[] signature = Base64.getDecoder().decode(VECTOR.path("signature").asString());

        assertThat(SigningKeys.verifyPrehashed(LEDGER, SIGNED, signature)).isTrue();
        assertThat(SigningKeys.verifyPrehashed(LEDGER, "other".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(RekorEntry.request(SIGNED, signature, LEDGER)).isEqualTo(VECTOR.path("request").asString());
    }

    @Test
    void anEntryForOtherDataOrAnotherKeyIsRejected() {
        assertThat(RekorEntry.verify(entry(), "other".getBytes(StandardCharsets.UTF_8), LEDGER, List.of(REKOR))
                .problem()).isEqualTo("the entry is for different data");
        assertThat(RekorEntry.verify(entry(), SIGNED, SigningKeys.generate().getPublic(), List.of(REKOR)).problem())
                .isEqualTo("the entry is signed by another key");
    }

    @Test
    void anotherRekorLogIsRejected() {
        NoteKey other = new NoteKey("rekor.local", NoteKey.ED25519, SigningKeys.generate().getPublic());

        assertThat(RekorEntry.verify(entry(), SIGNED, LEDGER, List.of(other)).problem())
                .isEqualTo("the log checkpoint is not signed by a pinned Rekor key");
    }

    @Test
    void aChangedProofIsRejected() {
        ObjectNode changed = entry();
        ((ObjectNode) changed.path("inclusionProof")).put("rootHash",
                Base64.getEncoder().encodeToString(new byte[32]));
        assertThat(RekorEntry.verify(changed, SIGNED, LEDGER, List.of(REKOR)).problem())
                .isEqualTo("the entry is not proven in the log");

        ObjectNode moved = entry();
        ((ObjectNode) moved.path("inclusionProof")).put("logIndex", "7");
        assertThat(RekorEntry.verify(moved, SIGNED, LEDGER, List.of(REKOR)).problem())
                .isEqualTo("the inclusion proof is for another entry");

        ObjectNode broken = entry();
        broken.put("canonicalizedBody", "not base64!");
        assertThat(RekorEntry.verify(broken, SIGNED, LEDGER, List.of(REKOR)).problem())
                .startsWith("the entry cannot be read");
    }
}
