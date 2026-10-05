package com.nexusphere.shared.id;

import java.util.Objects;
import java.util.UUID;

public record IdentityId(UUID value) implements Identifier {

    public IdentityId {
        Objects.requireNonNull(value, "IdentityId value must not be null");
    }

    public static IdentityId newId() {
        return new IdentityId(UUID.randomUUID());
    }

    public static IdentityId of(String value) {
        return new IdentityId(Identifier.parse(value, "IdentityId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
