package com.nexusphere.discovery.domain.model;

import com.nexusphere.capability.contract.CapabilitySnapshot;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryCriteriaTest {

    private final OrganizationId organization = OrganizationId.newId();

    private CapabilitySnapshot capability(String visibility, boolean available) {
        return new CapabilitySnapshot(CapabilityId.newId(), NetworkId.newId(), "ORGANIZATION", organization.value(),
                organization, "CNC", null, "manufacturing.cnc", 1, visibility, available);
    }

    @Test
    void localDiscoveryAdmitsNetworkAndFederatedButFederatedDiscoveryOnlyFederated() {
        DiscoveryCriteria any = new DiscoveryCriteria(null, null, null);

        assertThat(any.admits(capability("NETWORK", true), false)).isTrue();
        assertThat(any.admits(capability("FEDERATED", true), false)).isTrue();
        assertThat(any.admits(capability("PRIVATE", true), false)).isFalse();
        assertThat(any.admits(capability("NETWORK", true), true)).isFalse();
        assertThat(any.admits(capability("FEDERATED", true), true)).isTrue();
        assertThat(any.admits(capability("FEDERATED", false), true)).isFalse();
    }

    @Test
    void filtersNarrowByTypeOwnerAndOrganization() {
        CapabilitySnapshot cnc = capability("NETWORK", true);

        assertThat(new DiscoveryCriteria("manufacturing.cnc", "organization", organization).admits(cnc, false))
                .isTrue();
        assertThat(new DiscoveryCriteria("logistics.transport", null, null).admits(cnc, false)).isFalse();
        assertThat(new DiscoveryCriteria(null, "AGENT", null).admits(cnc, false)).isFalse();
        assertThat(new DiscoveryCriteria(null, null, new OrganizationId(UUID.randomUUID())).admits(cnc, false))
                .isFalse();
        assertThatThrownBy(() -> new DiscoveryCriteria(null, "ROBOT", null)).isInstanceOf(ValidationException.class);
    }
}
