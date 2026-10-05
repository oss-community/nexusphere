package com.nexusphere.organization.contract;

import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

public record OrganizationSnapshot(OrganizationId id, NetworkId networkId, String name, boolean active) {
}
