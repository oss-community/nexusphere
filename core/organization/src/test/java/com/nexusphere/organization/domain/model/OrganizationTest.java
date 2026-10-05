package com.nexusphere.organization.domain.model;

import com.nexusphere.organization.contract.OrganizationDeactivated;
import com.nexusphere.organization.contract.OrganizationRegistered;
import com.nexusphere.organization.contract.OrganizationRenamed;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizationTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    private static final NetworkId NETWORK = NetworkId.newId();

    @Test
    void registeredOrganizationIsActiveInItsNetwork() {
        Organization organization = Organization.register(OrganizationId.newId(), NETWORK, " Acme ", NOW);

        assertThat(organization.isActive()).isTrue();
        assertThat(organization.networkId()).isEqualTo(NETWORK);
        assertThat(organization.name()).isEqualTo("Acme");
        assertThat(organization.pullEvents()).singleElement().isInstanceOf(OrganizationRegistered.class);
    }

    @Test
    void nameIsRequired() {
        assertThatThrownBy(() -> Organization.register(OrganizationId.newId(), NETWORK, "", NOW))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ORGANIZATION_NAME");
    }

    @Test
    void renameAndDeactivateRaiseEvents() {
        Organization organization = Organization.register(OrganizationId.newId(), NETWORK, "Acme", NOW);
        organization.pullEvents();

        organization.rename("Acme Labs", NOW);
        organization.deactivate(NOW);

        assertThat(organization.name()).isEqualTo("Acme Labs");
        assertThat(organization.status()).isEqualTo(OrganizationStatus.DEACTIVATED);
        assertThat(organization.pullEvents()).hasExactlyElementsOfTypes(
                OrganizationRenamed.class, OrganizationDeactivated.class);
    }

    @Test
    void deactivatedOrganizationCannotChange() {
        Organization organization = Organization.register(OrganizationId.newId(), NETWORK, "Acme", NOW);
        organization.deactivate(NOW);

        assertThatThrownBy(() -> organization.rename("Other", NOW))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "ORGANIZATION_NOT_ACTIVE");
        assertThatThrownBy(() -> organization.deactivate(NOW))
                .hasFieldOrPropertyWithValue("code", "ORGANIZATION_NOT_ACTIVE");
    }
}
