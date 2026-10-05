package com.nexusphere.capability.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record CapabilityVisibilityChanged(UUID eventId, Instant occurredAt, CapabilityId capabilityId, NetworkId network,
                                          String visibility) implements DomainEvent {

    public CapabilityVisibilityChanged(Instant occurredAt, CapabilityId capabilityId, NetworkId network,
                                       String visibility) {
        this(UUID.randomUUID(), occurredAt, capabilityId, network, visibility);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(network);
    }
}
