package com.nexusphere.capability.domain.model;

import java.util.Objects;
import java.util.UUID;

public record CapabilityOwner(OwnerType type, UUID id) {

    public CapabilityOwner {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }
}
