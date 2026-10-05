package com.nexusphere.membership.domain.model;

import com.nexusphere.membership.contract.MembershipActivated;
import com.nexusphere.membership.contract.MembershipTerminated;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");

    @Test
    void activatedMembershipDefinesThePrincipal() {
        UUID id = UUID.randomUUID();
        Membership membership = Membership.activate(id, IdentityId.newId(), NetworkId.newId(), null, NOW);

        assertThat(membership.isActive()).isTrue();
        assertThat(membership.principalId().value()).isEqualTo(id);
        assertThat(membership.pullEvents()).singleElement().isInstanceOf(MembershipActivated.class);
    }

    @Test
    void terminationIsFinal() {
        Membership membership = Membership.activate(UUID.randomUUID(), IdentityId.newId(), NetworkId.newId(), null, NOW);
        membership.pullEvents();

        membership.terminate(NOW);

        assertThat(membership.isActive()).isFalse();
        assertThat(membership.terminatedAt()).contains(NOW);
        assertThat(membership.pullEvents()).singleElement().isInstanceOf(MembershipTerminated.class);
        assertThatThrownBy(() -> membership.terminate(NOW))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "MEMBERSHIP_ALREADY_TERMINATED");
    }
}
