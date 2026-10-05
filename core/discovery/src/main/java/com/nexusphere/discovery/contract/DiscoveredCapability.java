package com.nexusphere.discovery.contract;

import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

import java.util.UUID;

public record DiscoveredCapability(CapabilityId capabilityId, NetworkId originNetworkId, String originNetworkName,
                                   String name, String description, String typeCode, int typeVersion,
                                   String ownerType, UUID ownerId, OrganizationId accountableOrganizationId,
                                   String visibility, boolean federated, UUID federationId,
                                   UUID trustRelationshipId, UUID decisionId) {
}
