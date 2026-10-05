package com.nexusphere.identity.contract;

import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

public record IdentitySnapshot(IdentityId id, String type, String displayName, boolean active,
                               NetworkId owningNetworkId, OrganizationId owningOrganizationId) {

    public boolean ownedByOrganization() {
        return owningOrganizationId != null;
    }
}
