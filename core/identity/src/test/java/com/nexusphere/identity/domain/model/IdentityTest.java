package com.nexusphere.identity.domain.model;

import com.nexusphere.identity.contract.IdentityCreated;
import com.nexusphere.identity.contract.IdentitySuspended;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private static final Ownership OWNERSHIP = new Ownership(NetworkId.newId(), OrganizationId.newId());

    @Test
    void humanIdentityIsActiveAndUnowned() {
        Identity identity = Identity.create(IdentityId.newId(), IdentityType.HUMAN, " Alice ", null, null, null, NOW);

        assertThat(identity.isActive()).isTrue();
        assertThat(identity.displayName()).isEqualTo("Alice");
        assertThat(identity.ownership()).isEmpty();
        assertThat(identity.pullEvents()).singleElement().isInstanceOf(IdentityCreated.class);
    }

    @ParameterizedTest
    @EnumSource(value = IdentityType.class, names = {"AGENT", "MACHINE"})
    void agentsAndMachinesNeedAnOwningOrganization(IdentityType type) {
        assertThatThrownBy(() -> Identity.create(IdentityId.newId(), type, "Bot", null, null, null, NOW))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "OWNING_ORGANIZATION_REQUIRED");

        assertThat(Identity.create(IdentityId.newId(), type, "Bot", OWNERSHIP, null, null, NOW).ownership())
                .contains(OWNERSHIP);
    }

    @Test
    void humansCannotBeOwnedOrCarryAgentDescriptors() {
        assertThatThrownBy(() -> Identity.create(IdentityId.newId(), IdentityType.HUMAN, "Alice", OWNERSHIP, null, null, NOW))
                .hasFieldOrPropertyWithValue("code", "OWNING_ORGANIZATION_NOT_ALLOWED");
        assertThatThrownBy(() -> Identity.create(IdentityId.newId(), IdentityType.MACHINE, "Robot", OWNERSHIP, "x", null, NOW))
                .hasFieldOrPropertyWithValue("code", "AGENT_DESCRIPTOR_NOT_ALLOWED");
    }

    @Test
    void agentKeepsProviderAndModel() {
        Identity agent = Identity.create(IdentityId.newId(), IdentityType.AGENT, "Buyer", OWNERSHIP, "Anthropic",
                "local", NOW);

        assertThat(agent.agentProvider()).isEqualTo("Anthropic");
        assertThat(agent.agentModel()).isEqualTo("local");
    }

    @Test
    void suspendAndActivate() {
        Identity identity = Identity.create(IdentityId.newId(), IdentityType.SERVICE, "Svc", null, null, null, NOW);
        identity.pullEvents();

        identity.suspend(NOW);

        assertThat(identity.isActive()).isFalse();
        assertThat(identity.pullEvents()).singleElement().isInstanceOf(IdentitySuspended.class);
        assertThatThrownBy(() -> identity.suspend(NOW)).isInstanceOf(ConflictException.class);
        identity.activate(NOW);
        assertThat(identity.isActive()).isTrue();
    }
}
