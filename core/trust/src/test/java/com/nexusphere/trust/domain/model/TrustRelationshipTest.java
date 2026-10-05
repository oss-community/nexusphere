package com.nexusphere.trust.domain.model;

import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.trust.contract.TrustEstablished;
import com.nexusphere.trust.contract.TrustRevoked;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustRelationshipTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");

    private final Party networkA = Party.network(NetworkId.newId());
    private final Party networkB = Party.network(NetworkId.newId());

    @Test
    void trustIsDirectionalAndScoped() {
        TrustRelationship trust = TrustRelationship.establish(UUID.randomUUID(), networkA, networkB,
                List.of("capability:discover", " agreement:propose "), TrustLevel.HIGH, null, null, NOW);

        assertThat(trust.scopes()).containsExactly("agreement:propose", "capability:discover");
        assertThat(trust.covers("capability:discover", NOW)).isTrue();
        assertThat(trust.covers("transaction:initiate", NOW)).isFalse();
        assertThat(trust.source()).isEqualTo(networkA);
        assertThat(trust.target()).isEqualTo(networkB);
        assertThat(trust.pullEvents()).singleElement().isInstanceOf(TrustEstablished.class);
    }

    @Test
    void trustAppliesOnlyInsideItsPeriod() {
        Instant from = NOW.plus(Duration.ofHours(1));
        Instant until = NOW.plus(Duration.ofHours(2));
        TrustRelationship trust = TrustRelationship.establish(UUID.randomUUID(), networkA, networkB,
                List.of("capability:discover"), TrustLevel.MEDIUM, from, until, NOW);

        assertThat(trust.isEffective(NOW)).isFalse();
        assertThat(trust.isCurrent(NOW)).isTrue();
        assertThat(trust.isEffective(from)).isTrue();
        assertThat(trust.isEffective(until)).isFalse();
        assertThat(trust.isCurrent(until)).isFalse();
    }

    @Test
    void revocationIsFinal() {
        TrustRelationship trust = TrustRelationship.establish(UUID.randomUUID(), networkA, networkB,
                List.of("capability:discover"), TrustLevel.MEDIUM, null, null, NOW);
        trust.pullEvents();

        trust.revoke(NOW);

        assertThat(trust.isEffective(NOW)).isFalse();
        assertThat(trust.revokedAt()).contains(NOW);
        assertThat(trust.pullEvents()).singleElement().isInstanceOf(TrustRevoked.class);
        assertThatThrownBy(() -> trust.revoke(NOW))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "TRUST_ALREADY_REVOKED");
    }

    @Test
    void invalidTrustIsRejected() {
        assertThatThrownBy(() -> TrustRelationship.establish(UUID.randomUUID(), networkA, networkA,
                List.of("capability:discover"), TrustLevel.MEDIUM, null, null, NOW))
                .hasFieldOrPropertyWithValue("code", "TRUST_WITH_ITSELF");
        assertThatThrownBy(() -> TrustRelationship.establish(UUID.randomUUID(), networkA, networkB, List.of(),
                TrustLevel.MEDIUM, null, null, NOW))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "TRUST_SCOPE_REQUIRED");
        assertThatThrownBy(() -> TrustRelationship.establish(UUID.randomUUID(), networkA, networkB,
                List.of("TRUSTED"), TrustLevel.MEDIUM, null, null, NOW))
                .hasFieldOrPropertyWithValue("code", "INVALID_TRUST_SCOPE");
        assertThatThrownBy(() -> TrustRelationship.establish(UUID.randomUUID(), networkA, networkB,
                List.of("capability:discover"), TrustLevel.MEDIUM, null, NOW.minusSeconds(1), NOW))
                .hasFieldOrPropertyWithValue("code", "INVALID_TRUST_PERIOD");
        assertThatThrownBy(() -> new Party(PartyType.NETWORK, UUID.randomUUID(), NetworkId.newId()))
                .hasFieldOrPropertyWithValue("code", "INVALID_PARTY");
    }
}
