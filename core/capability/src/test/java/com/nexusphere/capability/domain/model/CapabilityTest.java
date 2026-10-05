package com.nexusphere.capability.domain.model;

import com.nexusphere.capability.contract.CapabilityPublished;
import com.nexusphere.capability.contract.CapabilityRegistered;
import com.nexusphere.capability.contract.CapabilityVisibilityChanged;
import com.nexusphere.capability.contract.CapabilityWithdrawn;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private static final CapabilityType CNC = CapabilityType.register(UUID.randomUUID(), "manufacturing.cnc",
            "CNC machining", null, 1, CapabilitySchema.of(CapabilitySchemaTest.CNC), NOW);
    private static final Map<String, Object> SPEC = Map.of("material", "steel", "maxPartSizeMm", 500);

    private final OrganizationId organization = OrganizationId.newId();
    private final IdentityId agent = IdentityId.newId();

    private Capability agentCapability(CapabilityVisibility visibility) {
        Capability capability = Capability.register(CapabilityId.newId(), NetworkId.newId(),
                new CapabilityOwner(OwnerType.AGENT, agent.value()), organization, " Precision CNC ", null, CNC,
                SPEC, visibility, NOW);
        capability.pullEvents();
        return capability;
    }

    @Test
    void registrationStartsAsDraftAndCarriesTheTypeVersion() {
        Capability capability = Capability.register(CapabilityId.newId(), NetworkId.newId(),
                new CapabilityOwner(OwnerType.ORGANIZATION, organization.value()), organization, " Precision CNC ",
                "Five-axis milling", CNC, SPEC, CapabilityVisibility.NETWORK, NOW);

        assertThat(capability.name()).isEqualTo("Precision CNC");
        assertThat(capability.status()).isEqualTo(CapabilityStatus.DRAFT);
        assertThat(capability.typeCode()).isEqualTo("manufacturing.cnc");
        assertThat(capability.typeVersion()).isEqualTo(1);
        assertThat(capability.isAvailable()).isFalse();
        assertThat(capability.pullEvents()).singleElement().isInstanceOf(CapabilityRegistered.class);
    }

    @Test
    void specificationMustMatchTheTypeSchema() {
        assertThatThrownBy(() -> Capability.register(CapabilityId.newId(), NetworkId.newId(),
                new CapabilityOwner(OwnerType.AGENT, agent.value()), organization, "CNC", null, CNC,
                Map.of("material", "wood"), CapabilityVisibility.NETWORK, NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "CAPABILITY_SPECIFICATION_INVALID");
        assertThatThrownBy(() -> Capability.register(CapabilityId.newId(), NetworkId.newId(),
                new CapabilityOwner(OwnerType.AGENT, agent.value()), organization, " ", null, CNC, SPEC,
                CapabilityVisibility.NETWORK, NOW))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_CAPABILITY_NAME");
    }

    @Test
    void publishingMakesNonPrivateCapabilitiesDiscoverable() {
        Capability capability = agentCapability(CapabilityVisibility.PRIVATE);

        capability.publish(CapabilityVisibility.NETWORK, NOW);

        assertThat(capability.status()).isEqualTo(CapabilityStatus.PUBLISHED);
        assertThat(capability.visibility()).isEqualTo(CapabilityVisibility.NETWORK);
        assertThat(capability.publishedAt()).contains(NOW);
        assertThat(capability.isDiscoverable()).isTrue();
        assertThat(capability.pullEvents()).singleElement().isInstanceOf(CapabilityPublished.class);
        assertThatThrownBy(() -> capability.publish(null, NOW))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "CAPABILITY_ALREADY_PUBLISHED");
    }

    @Test
    void privateCapabilitiesAreVisibleOnlyToTheirManagers() {
        Capability capability = agentCapability(CapabilityVisibility.PRIVATE);
        capability.publish(null, NOW);

        assertThat(capability.isDiscoverable()).isFalse();
        assertThat(capability.isVisibleTo(agent, null)).isTrue();
        assertThat(capability.isVisibleTo(IdentityId.newId(), organization)).isTrue();
        assertThat(capability.isVisibleTo(IdentityId.newId(), OrganizationId.newId())).isFalse();
        assertThat(capability.isVisibleTo(IdentityId.newId(), null)).isFalse();
    }

    @Test
    void organizationOwnersAreNotManagedByAnIdentityWithTheSameId() {
        UUID shared = UUID.randomUUID();

        assertThat(Capability.manages(new CapabilityOwner(OwnerType.ORGANIZATION, shared), null,
                new IdentityId(shared), null)).isFalse();
    }

    @Test
    void visibilityChangesAreRecorded() {
        Capability capability = agentCapability(CapabilityVisibility.NETWORK);

        capability.changeVisibility(CapabilityVisibility.NETWORK, NOW);
        capability.changeVisibility(CapabilityVisibility.FEDERATED, NOW);

        assertThat(capability.visibility()).isEqualTo(CapabilityVisibility.FEDERATED);
        assertThat(capability.pullEvents()).singleElement().isInstanceOf(CapabilityVisibilityChanged.class);
    }

    @Test
    void withdrawalIsFinal() {
        Capability capability = agentCapability(CapabilityVisibility.NETWORK);
        capability.publish(null, NOW);
        capability.pullEvents();

        capability.withdraw(NOW);

        assertThat(capability.status()).isEqualTo(CapabilityStatus.WITHDRAWN);
        assertThat(capability.isAvailable()).isFalse();
        assertThat(capability.isDiscoverable()).isFalse();
        assertThat(capability.isVisibleTo(agent, null)).isTrue();
        assertThat(capability.pullEvents()).singleElement().isInstanceOf(CapabilityWithdrawn.class);
        assertThatThrownBy(() -> capability.withdraw(NOW))
                .hasFieldOrPropertyWithValue("code", "CAPABILITY_WITHDRAWN");
        assertThatThrownBy(() -> capability.publish(null, NOW))
                .hasFieldOrPropertyWithValue("code", "CAPABILITY_WITHDRAWN");
        assertThatThrownBy(() -> capability.changeVisibility(CapabilityVisibility.PRIVATE, NOW))
                .hasFieldOrPropertyWithValue("code", "CAPABILITY_WITHDRAWN");
    }

    @Test
    void typeCodesAreDotSeparatedLowercaseSegments() {
        assertThatThrownBy(() -> CapabilityType.register(UUID.randomUUID(), "Manufacturing CNC", "CNC", null, 1,
                CapabilitySchema.of(CapabilitySchemaTest.CNC), NOW))
                .hasFieldOrPropertyWithValue("code", "INVALID_CAPABILITY_TYPE_CODE");
    }
}
