package com.nexusphere.discovery.contract;

import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

public record CapabilityQuery(String typeCode, String ownerType, OrganizationId organizationId,
                              NetworkId originNetworkId, String scope) {

    public static CapabilityQuery all() {
        return new CapabilityQuery(null, null, null, null, null);
    }

    public static CapabilityQuery ofType(String typeCode) {
        return new CapabilityQuery(typeCode, null, null, null, null);
    }
}
