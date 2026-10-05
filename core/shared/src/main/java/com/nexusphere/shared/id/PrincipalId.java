package com.nexusphere.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Globally unique identifier of a principal. Carries no business meaning. */
public record PrincipalId(UUID value) implements Identifier {

    public PrincipalId {
        Objects.requireNonNull(value, "PrincipalId value must not be null");
    }

    public static PrincipalId newId() {
        return new PrincipalId(UUID.randomUUID());
    }

    public static PrincipalId of(String value) {
        return new PrincipalId(Identifier.parse(value, "PrincipalId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
