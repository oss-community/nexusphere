package com.nexusphere.network.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record NetworkArchived(UUID eventId, Instant occurredAt, NetworkId network) implements DomainEvent {

    public NetworkArchived(Instant occurredAt, NetworkId network) {
        this(UUID.randomUUID(), occurredAt, network);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(network);
    }
}
