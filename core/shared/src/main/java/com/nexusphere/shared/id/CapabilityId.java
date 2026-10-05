package com.nexusphere.shared.id;

import java.util.Objects;
import java.util.UUID;

public record CapabilityId(UUID value) implements Identifier {

    public CapabilityId {
        Objects.requireNonNull(value, "CapabilityId value must not be null");
    }

    public static CapabilityId newId() {
        return new CapabilityId(UUID.randomUUID());
    }

    public static CapabilityId of(String value) {
        return new CapabilityId(Identifier.parse(value, "CapabilityId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
