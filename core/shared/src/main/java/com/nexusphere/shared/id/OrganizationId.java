package com.nexusphere.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Globally unique identifier of a organization. Carries no business meaning. */
public record OrganizationId(UUID value) implements Identifier {

    public OrganizationId {
        Objects.requireNonNull(value, "OrganizationId value must not be null");
    }

    public static OrganizationId newId() {
        return new OrganizationId(UUID.randomUUID());
    }

    public static OrganizationId of(String value) {
        return new OrganizationId(Identifier.parse(value, "OrganizationId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
