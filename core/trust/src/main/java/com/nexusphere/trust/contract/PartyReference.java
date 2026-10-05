package com.nexusphere.trust.contract;

import com.nexusphere.shared.id.NetworkId;

import java.util.Objects;
import java.util.UUID;

public record PartyReference(String type, UUID id, NetworkId networkId) {

    public PartyReference {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(networkId, "networkId must not be null");
    }

    public static PartyReference network(NetworkId networkId) {
        return new PartyReference("NETWORK", networkId.value(), networkId);
    }
}
