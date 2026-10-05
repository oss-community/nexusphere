package com.nexusphere.capability.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record CapabilityRegistered(UUID eventId, Instant occurredAt, CapabilityId capabilityId, NetworkId network,
                                   String ownerType, UUID ownerId, String typeCode) implements DomainEvent {

    public CapabilityRegistered(Instant occurredAt, CapabilityId capabilityId, NetworkId network, String ownerType,
                                UUID ownerId, String typeCode) {
        this(UUID.randomUUID(), occurredAt, capabilityId, network, ownerType, ownerId, typeCode);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(network);
    }
}
