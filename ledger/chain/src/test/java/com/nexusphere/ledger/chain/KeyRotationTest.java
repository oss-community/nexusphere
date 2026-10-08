package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeyRotationTest {

    private final KeyPair first = SigningKeys.generate();
    private final KeyPair second = SigningKeys.generate();
    private final KeyPair third = SigningKeys.generate();
    private final SigningKeys.PublicKeyInfo firstKey = SigningKeys.PublicKeyInfo.of(first.getPublic());
    private final SigningKeys.PublicKeyInfo secondKey = SigningKeys.PublicKeyInfo.of(second.getPublic());
    private final SigningKeys.PublicKeyInfo thirdKey = SigningKeys.PublicKeyInfo.of(third.getPublic());

    private KeyRotation endorsed(SigningKeys.PublicKeyInfo key, KeyPair pair, SigningKeys.PublicKeyInfo previous,
                                 KeyPair previousPair) {
        return KeyRotation.issue(key, pair.getPrivate(), previous.keyId(), previousPair.getPrivate(), Instant.now());
    }

    @Test
    void anEndorsedRotationVerifiesWithBothKeys() {
        KeyRotation rotation = endorsed(secondKey, second, firstKey, first);

        assertThat(rotation.verifiedByKey()).isTrue();
        assertThat(rotation.verifiedByPrevious(firstKey)).isTrue();
        assertThat(rotation.verifiedByPrevious(thirdKey)).isFalse();
    }

    @Test
    void aRotationWithAnotherPublicKeyDoesNotVerify() {
        KeyRotation rotation = endorsed(secondKey, second, firstKey, first);
        KeyRotation swapped = new KeyRotation(rotation.keyId(), thirdKey.encoded(), rotation.previousKeyId(),
                rotation.activatedAt(), rotation.keySignature(), rotation.previousKeySignature());

        assertThat(swapped.verifiedByKey()).isFalse();
        assertThat(swapped.verifiedByPrevious(firstKey)).isFalse();
    }

    @Test
    void trustFollowsEndorsementsForwardAndRotationsBackward() {
        List<KeyRotation> rotations = List.of(endorsed(secondKey, second, firstKey, first),
                endorsed(thirdKey, third, secondKey, second));
        List<SigningKeys.PublicKeyInfo> keys = List.of(firstKey, secondKey, thirdKey);

        assertThat(TrustedKeys.from(firstKey, keys, rotations).find(thirdKey.keyId())).contains(thirdKey);
        assertThat(TrustedKeys.from(thirdKey, keys, rotations).find(firstKey.keyId())).contains(firstKey);
    }

    @Test
    void anUnendorsedRotationIsTrustedOnlyBackward() {
        KeyRotation rotation = KeyRotation.issue(secondKey, second.getPrivate(), firstKey.keyId(), null,
                Instant.now());
        List<SigningKeys.PublicKeyInfo> keys = List.of(firstKey, secondKey);

        assertThat(rotation.endorsed()).isFalse();
        assertThat(TrustedKeys.from(firstKey, keys, List.of(rotation)).find(secondKey.keyId())).isEmpty();
        assertThat(TrustedKeys.from(secondKey, keys, List.of(rotation)).find(firstKey.keyId())).contains(firstKey);
    }

    @Test
    void aForeignKeyClaimingAPredecessorIsNotTrusted() {
        KeyRotation forged = KeyRotation.issue(thirdKey, third.getPrivate(), firstKey.keyId(), null, Instant.now());
        List<SigningKeys.PublicKeyInfo> keys = List.of(firstKey, thirdKey);

        assertThat(TrustedKeys.from(firstKey, keys, List.of(forged)).find(thirdKey.keyId())).isEmpty();
    }
}
