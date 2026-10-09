package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyRevocationTest {

    private final KeyPair first = SigningKeys.generate();
    private final KeyPair second = SigningKeys.generate();
    private final KeyPair attacker = SigningKeys.generate();
    private final SigningKeys.PublicKeyInfo firstKey = SigningKeys.PublicKeyInfo.of(first.getPublic());
    private final SigningKeys.PublicKeyInfo secondKey = SigningKeys.PublicKeyInfo.of(second.getPublic());
    private final SigningKeys.PublicKeyInfo attackerKey = SigningKeys.PublicKeyInfo.of(attacker.getPublic());
    private final Instant compromisedAt = Instant.parse("2026-10-01T12:00:00Z");
    private final List<SigningKeys.PublicKeyInfo> keys = List.of(firstKey, secondKey, attackerKey);

    private KeyRevocation revokeFirst(Instant compromised) {
        return KeyRevocation.issue(firstKey.keyId(), compromised, compromised.plus(1, ChronoUnit.HOURS),
                "key leaked", secondKey.keyId(), Signer.of(second.getPrivate()));
    }

    private KeyRotation rotation(SigningKeys.PublicKeyInfo key, KeyPair pair, Instant activatedAt) {
        return KeyRotation.issue(key, pair.getPrivate(), firstKey.keyId(), first.getPrivate(), activatedAt);
    }

    @Test
    void aRevocationVerifiesOnlyWithItsRevoker() {
        KeyRevocation revocation = revokeFirst(compromisedAt);
        KeyRevocation changed = new KeyRevocation(revocation.keyId(), compromisedAt.plusSeconds(60),
                revocation.revokedAt(), revocation.reason(), revocation.revokerKeyId(), revocation.signature());

        assertThat(revocation.verify(secondKey)).isTrue();
        assertThat(revocation.verify(firstKey)).isFalse();
        assertThat(changed.verify(secondKey)).isFalse();
        assertThat(revocation.covers(compromisedAt)).isTrue();
        assertThat(revocation.covers(compromisedAt.minusMillis(1))).isFalse();
    }

    @Test
    void aKeyCannotRevokeItself() {
        assertThatThrownBy(() -> KeyRevocation.issue(firstKey.keyId(), compromisedAt, compromisedAt, "lost",
                firstKey.keyId(), Signer.of(first.getPrivate()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTrustedRevokerRevokesItsPredecessor() {
        TrustedKeys trusted = TrustedKeys.from(secondKey, keys,
                List.of(rotation(secondKey, second, compromisedAt.minusSeconds(3600))),
                List.of(revokeFirst(compromisedAt)));

        assertThat(trusted.find(firstKey.keyId())).contains(firstKey);
        assertThat(trusted.revocation(firstKey.keyId())).map(KeyRevocation::compromisedAt).contains(compromisedAt);
        assertThat(trusted.revocation(secondKey.keyId())).isEmpty();
    }

    @Test
    void anUntrustedKeyCannotRevoke() {
        KeyRevocation forged = KeyRevocation.issue(secondKey.keyId(), compromisedAt, compromisedAt, "forged",
                attackerKey.keyId(), Signer.of(attacker.getPrivate()));

        TrustedKeys trusted = TrustedKeys.from(secondKey, keys, List.of(), List.of(forged));

        assertThat(trusted.revocation(secondKey.keyId())).isEmpty();
    }

    @Test
    void aRevokedKeyEndorsesNoKeyAfterItsCompromise() {
        List<KeyRotation> rotations = List.of(rotation(secondKey, second, compromisedAt.minusSeconds(3600)),
                rotation(attackerKey, attacker, compromisedAt.plusSeconds(60)));

        TrustedKeys withoutRevocation = TrustedKeys.from(secondKey, keys, rotations, List.of());
        TrustedKeys revoked = TrustedKeys.from(secondKey, keys, rotations, List.of(revokeFirst(compromisedAt)));

        assertThat(withoutRevocation.find(attackerKey.keyId())).isPresent();
        assertThat(revoked.find(attackerKey.keyId())).isEmpty();
        assertThat(revoked.find(firstKey.keyId())).isPresent();
    }

    @Test
    void listedKeysRevokeEachOther() {
        TrustedKeys listed = TrustedKeys.listed(keys, List.of(revokeFirst(compromisedAt)));

        assertThat(listed.revocation(firstKey.keyId())).isPresent();
        assertThat(listed.all()).hasSize(3);
    }
}
