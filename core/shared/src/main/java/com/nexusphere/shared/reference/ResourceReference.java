package com.nexusphere.shared.reference;

import com.nexusphere.shared.id.NetworkId;

import java.util.Objects;

public record ResourceReference(String resourceType, String resourceId, NetworkId networkId) {

    public ResourceReference {
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(resourceId, "resourceId must not be null");
        Objects.requireNonNull(networkId, "networkId must not be null");
        if (resourceType.isBlank() || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceType and resourceId must not be blank");
        }
    }
}
