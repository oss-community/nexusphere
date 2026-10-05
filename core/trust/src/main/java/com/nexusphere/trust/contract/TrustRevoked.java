package com.nexusphere.trust.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record TrustRevoked(UUID eventId, Instant occurredAt, UUID trustRelationshipId, PartyReference source,
                           PartyReference target) implements DomainEvent {

    public TrustRevoked(Instant occurredAt, UUID trustRelationshipId, PartyReference source, PartyReference target) {
        this(UUID.randomUUID(), occurredAt, trustRelationshipId, source, target);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(source.networkId());
    }
}
