package com.nexusphere.trust.domain.model;

import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.trust.contract.PartyReference;

import java.util.Objects;
import java.util.UUID;

public record Party(PartyType type, UUID id, NetworkId networkId) {

    public Party {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(networkId, "networkId must not be null");
        if (type == PartyType.NETWORK && !networkId.value().equals(id)) {
            throw new ValidationException("INVALID_PARTY", "A network party belongs to itself");
        }
    }

    public static Party network(NetworkId networkId) {
        return new Party(PartyType.NETWORK, networkId.value(), networkId);
    }

    public PartyReference reference() {
        return new PartyReference(type.name(), id, networkId);
    }
}
