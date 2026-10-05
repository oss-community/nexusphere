package com.nexusphere.identity.domain.model;

import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

import java.util.Objects;

public record Ownership(NetworkId networkId, OrganizationId organizationId) {

    public Ownership {
        Objects.requireNonNull(networkId, "networkId must not be null");
        Objects.requireNonNull(organizationId, "organizationId must not be null");
    }
}
