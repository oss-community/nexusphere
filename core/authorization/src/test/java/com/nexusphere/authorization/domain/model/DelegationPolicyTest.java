package com.nexusphere.authorization.domain.model;

import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.authorization.contract.DelegationEvidence;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DelegationPolicyTest {

    private final NetworkId home = NetworkId.newId();
    private final NetworkId partner = NetworkId.newId();
    private final PrincipalId manager = PrincipalId.newId();
    private final PrincipalId agent = PrincipalId.newId();
    private final Instant now = Instant.parse("2026-10-05T10:00:00Z");
    private final Optional<Set<Role>> managerRoles = Optional.of(EnumSet.of(Role.MEMBER, Role.AGREEMENT_MANAGER));

    private DelegationEvidence delegation(String status, Instant validUntil, Set<String> capabilityTypes,
                                          Set<NetworkId> networks) {
        return new DelegationEvidence(UUID.randomUUID(), home, manager, agent, Set.of(Actions.AGREEMENT_PROPOSE),
                capabilityTypes, networks, Set.of(), now.minus(Duration.ofHours(1)), validUntil, status,
                now.minus(Duration.ofHours(1)));
    }

    private DelegationPolicy.Facts facts(PrincipalId principal, String action, NetworkId target, String capabilityType) {
        return new DelegationPolicy.Facts(principal, home, action, target, "agreement", capabilityType, now);
    }

    @Test
    void delegatedActionIsAllowedWithTheDelegatorsRole() {
        AuthorizationPolicy.Outcome outcome = DelegationPolicy.evaluate(delegation("ACTIVE", null, Set.of(), Set.of()),
                facts(agent, Actions.AGREEMENT_PROPOSE, home, null), managerRoles);

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.reason()).isEqualTo("DELEGATION_GRANTED");
        assertThat(outcome.matchedRole()).isEqualTo(Role.AGREEMENT_MANAGER);
        assertThat(DelegationPolicy.evaluate(delegation("ACTIVE", null, Set.of(), Set.of()),
                facts(agent, Actions.AGREEMENT_ACCEPT, home, null), managerRoles).reason())
                .isEqualTo("DELEGATION_SCOPE_VIOLATION");
    }

    @Test
    void lifecycleAndOwnershipAreChecked() {
        DelegationPolicy.Facts propose = facts(agent, Actions.AGREEMENT_PROPOSE, home, null);

        assertThat(DelegationPolicy.evaluate(delegation("ACTIVE", null, Set.of(), Set.of()),
                facts(PrincipalId.newId(), Actions.AGREEMENT_PROPOSE, home, null), managerRoles).reason())
                .isEqualTo("DELEGATION_INVALID");
        assertThat(DelegationPolicy.evaluate(delegation("REVOKED", null, Set.of(), Set.of()), propose, managerRoles)
                .reason()).isEqualTo("DELEGATION_REVOKED");
        assertThat(DelegationPolicy.evaluate(delegation("SUSPENDED", null, Set.of(), Set.of()), propose, managerRoles)
                .reason()).isEqualTo("DELEGATION_SUSPENDED");
        assertThat(DelegationPolicy.evaluate(delegation("ACTIVE", now, Set.of(), Set.of()), propose, managerRoles)
                .reason()).isEqualTo("DELEGATION_EXPIRED");
        assertThat(DelegationPolicy.evaluate(delegation("ACTIVE", null, Set.of(), Set.of()), propose,
                Optional.of(EnumSet.of(Role.MEMBER))).reason()).isEqualTo("DELEGATOR_AUTHORITY_LOST");
        assertThat(DelegationPolicy.evaluate(delegation("ACTIVE", null, Set.of(), Set.of()), propose, Optional.empty())
                .reason()).isEqualTo("DELEGATOR_AUTHORITY_LOST");
    }

    @Test
    void constraintsLimitCapabilityTypeAndNetwork() {
        DelegationEvidence constrained = delegation("ACTIVE", null, Set.of("manufacturing.cnc"), Set.of(partner));

        assertThat(DelegationPolicy.evaluate(constrained,
                facts(agent, Actions.AGREEMENT_PROPOSE, partner, "manufacturing.cnc"), managerRoles).allowed()).isTrue();
        assertThat(DelegationPolicy.evaluate(constrained,
                facts(agent, Actions.AGREEMENT_PROPOSE, partner, "logistics.transport"), managerRoles).reason())
                .isEqualTo("DELEGATION_CONSTRAINT_VIOLATION");
        assertThat(DelegationPolicy.evaluate(constrained,
                facts(agent, Actions.AGREEMENT_PROPOSE, home, "manufacturing.cnc"), managerRoles).reason())
                .isEqualTo("DELEGATION_CONSTRAINT_VIOLATION");
    }
}
