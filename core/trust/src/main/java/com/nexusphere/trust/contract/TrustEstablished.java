package com.nexusphere.trust.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record TrustEstablished(UUID eventId, Instant occurredAt, UUID trustRelationshipId, PartyReference source,
                               PartyReference target, Set<String> scopes) implements DomainEvent {

    public TrustEstablished(Instant occurredAt, UUID trustRelationshipId, PartyReference source,
                            PartyReference target, Set<String> scopes) {
        this(UUID.randomUUID(), occurredAt, trustRelationshipId, source, target, scopes);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(source.networkId());
    }
}
