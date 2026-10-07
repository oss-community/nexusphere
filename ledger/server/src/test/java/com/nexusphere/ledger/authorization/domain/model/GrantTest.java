package com.nexusphere.ledger.authorization.domain.model;

import com.nexusphere.ledger.chain.GrantTerms;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GrantTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private static Grant grant(Instant notBefore, Instant expiresAt, Long maxUses, long uses, GrantState state) {
        GrantTerms terms = new GrantTerms(UUID.randomUUID(), "alice", "agent", List.of("*"), List.of("*"),
                notBefore, expiresAt, maxUses, NOW.minusSeconds(60));
        return new Grant(1, terms, uses, state, null, null, null);
    }

    @Test
    void statusFollowsTheTermsOfTheGrant() {
        Instant later = NOW.plusSeconds(3600);

        assertThat(grant(null, later, null, 0, GrantState.ACTIVE).status(NOW)).isEqualTo(GrantStatus.ACTIVE);
        assertThat(grant(null, later, 3L, 3, GrantState.ACTIVE).status(NOW)).isEqualTo(GrantStatus.EXHAUSTED);
        assertThat(grant(NOW.plusSeconds(1), later, null, 0, GrantState.ACTIVE).status(NOW))
                .isEqualTo(GrantStatus.NOT_YET_VALID);
        assertThat(grant(null, NOW, null, 0, GrantState.ACTIVE).status(NOW)).isEqualTo(GrantStatus.EXPIRED);
        assertThat(grant(null, later, null, 0, GrantState.REVOKED).status(NOW)).isEqualTo(GrantStatus.REVOKED);
    }
}
