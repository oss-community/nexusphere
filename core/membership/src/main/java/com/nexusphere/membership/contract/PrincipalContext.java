package com.nexusphere.membership.contract;

import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;

import java.util.Objects;

public record PrincipalContext(PrincipalId principalId, IdentityId identityId, String identityType,
                               NetworkId networkId, OrganizationId organizationId) {

    public PrincipalContext {
        Objects.requireNonNull(principalId, "principalId must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(identityType, "identityType must not be null");
        Objects.requireNonNull(networkId, "networkId must not be null");
    }
}
