package com.nexusphere.delegation.domain.model;

import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.delegation.contract.DelegationGranted;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationTest {

    private final NetworkId network = NetworkId.newId();
    private final PrincipalId manager = PrincipalId.newId();
    private final PrincipalId agent = PrincipalId.newId();
    private final Instant now = Instant.parse("2026-10-05T10:00:00Z");

    private Delegation grant(List<String> actions, Instant validUntil) {
        return Delegation.grant(UUID.randomUUID(), network, manager, agent, actions, DelegationConstraints.none(), null,
                validUntil, now);
    }

    @Test
    void grantingRecordsAnEventAndIsEffectiveUntilExpiry() {
        Delegation delegation = grant(List.of(Actions.AGREEMENT_PROPOSE), now.plus(Duration.ofDays(1)));

        assertThat(delegation.pullEvents()).singleElement().isInstanceOf(DelegationGranted.class);
        assertThat(delegation.isEffective(now)).isTrue();
        assertThat(delegation.effectiveStatus(now.plus(Duration.ofDays(2)))).isEqualTo(DelegationStatus.EXPIRED);
        assertThat(delegation.isEffective(now.plus(Duration.ofDays(2)))).isFalse();
    }

    @Test
    void invalidGrantsAreRejected() {
        assertThatThrownBy(() -> Delegation.grant(UUID.randomUUID(), network, manager, manager,
                List.of(Actions.AGREEMENT_PROPOSE), null, null, null, now))
                .isInstanceOf(DomainException.class).hasFieldOrPropertyWithValue("code", "DELEGATION_TO_SELF");
        assertThatThrownBy(() -> grant(List.of(Actions.DELEGATION_GRANT), null))
                .isInstanceOf(DomainException.class).hasFieldOrPropertyWithValue("code", "DELEGATION_DEPTH_EXCEEDED");
        assertThatThrownBy(() -> grant(List.of("payment:send"), null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> grant(List.of(Actions.AGREEMENT_PROPOSE), now.minusSeconds(1)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void suspendResumeAndRevokeFollowTheLifecycle() {
        Delegation delegation = grant(List.of(Actions.AGREEMENT_PROPOSE), null);

        delegation.suspend(now);
        assertThat(delegation.isEffective(now)).isFalse();
        assertThatThrownBy(() -> delegation.suspend(now)).isInstanceOf(ConflictException.class);
        delegation.resume(now);
        assertThat(delegation.isEffective(now)).isTrue();
        delegation.revoke(now);
        assertThat(delegation.status()).isEqualTo(DelegationStatus.REVOKED);
        assertThatThrownBy(() -> delegation.resume(now)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> delegation.revoke(now)).isInstanceOf(ConflictException.class);
    }
}
