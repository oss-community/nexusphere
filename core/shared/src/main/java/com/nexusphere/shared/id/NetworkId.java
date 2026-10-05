package com.nexusphere.shared.id;

import java.util.Objects;
import java.util.UUID;

public record NetworkId(UUID value) implements Identifier {

    public NetworkId {
        Objects.requireNonNull(value, "NetworkId value must not be null");
    }

    public static NetworkId newId() {
        return new NetworkId(UUID.randomUUID());
    }

    public static NetworkId of(String value) {
        return new NetworkId(Identifier.parse(value, "NetworkId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
