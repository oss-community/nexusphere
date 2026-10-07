package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedCheckpointTest {

    private final KeyPair keys = SigningKeys.generate();
    private final SigningKeys.PublicKeyInfo publicKey = SigningKeys.PublicKeyInfo.of(keys.getPublic());

    private SignedCheckpoint signed(long sequence, String headHash) {
        Checkpoint checkpoint = new Checkpoint(sequence, headHash, Instant.now(), publicKey.keyId());
        return new SignedCheckpoint(checkpoint, SigningKeys.sign(keys.getPrivate(), checkpoint.signedBytes()));
    }

    @Test
    void aSignedCheckpointVerifiesWithItsPublicKey() {
        assertThat(signed(10, Hashes.sha256(new byte[]{2})).verify(publicKey)).isTrue();
    }

    @Test
    void aChangedCheckpointNoLongerVerifies() {
        SignedCheckpoint original = signed(10, Hashes.sha256(new byte[]{2}));
        Checkpoint changed = new Checkpoint(11, original.checkpoint().headHash(), original.checkpoint().createdAt(),
                original.checkpoint().keyId());

        assertThat(new SignedCheckpoint(changed, original.signature()).verify(publicKey)).isFalse();
    }

    @Test
    void anotherKeyDoesNotVerify() {
        SigningKeys.PublicKeyInfo other = SigningKeys.PublicKeyInfo.of(SigningKeys.generate().getPublic());

        assertThat(signed(1, Hashes.GENESIS).verify(other)).isFalse();
    }

    @Test
    void keysSurviveEncodingRoundTrips() {
        var privateKey = SigningKeys.decodePrivate(SigningKeys.encode(keys.getPrivate()));
        var decodedPublic = SigningKeys.decodePublic(publicKey.encoded());

        assertThat(SigningKeys.matches(privateKey, decodedPublic)).isTrue();
        assertThat(SigningKeys.keyIdOf(decodedPublic)).isEqualTo(publicKey.keyId()).hasSize(16);
    }

    @Test
    void malformedKeysAreRejected() {
        assertThatThrownBy(() -> SigningKeys.decodePrivate("not-a-key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SigningKeys.decodePublic("bm90LWEta2V5"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
