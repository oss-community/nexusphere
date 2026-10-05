package com.nexusphere.capability.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record CapabilityWithdrawn(UUID eventId, Instant occurredAt, CapabilityId capabilityId, NetworkId network)
        implements DomainEvent {

    public CapabilityWithdrawn(Instant occurredAt, CapabilityId capabilityId, NetworkId network) {
        this(UUID.randomUUID(), occurredAt, capabilityId, network);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(network);
    }
}
