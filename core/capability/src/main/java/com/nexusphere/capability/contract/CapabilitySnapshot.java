package com.nexusphere.capability.contract;

import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

import java.util.UUID;

public record CapabilitySnapshot(CapabilityId id, NetworkId networkId, String ownerType, UUID ownerId,
                                 OrganizationId accountableOrganizationId, String name, String typeCode,
                                 int typeVersion, String visibility, boolean available) {
}
