package com.nexusphere.federation.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record FederationProposed(UUID eventId, Instant occurredAt, UUID federationId, NetworkId network,
                                 NetworkId partnerNetwork) implements DomainEvent {

    public FederationProposed(Instant occurredAt, UUID federationId, NetworkId network, NetworkId partnerNetwork) {
        this(UUID.randomUUID(), occurredAt, federationId, network, partnerNetwork);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(network);
    }
}
