package com.nexusphere.network.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record NetworkCreated(UUID eventId, Instant occurredAt, NetworkId network, String name) implements DomainEvent {

    public NetworkCreated(Instant occurredAt, NetworkId network, String name) {
        this(UUID.randomUUID(), occurredAt, network, name);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(network);
    }
}
