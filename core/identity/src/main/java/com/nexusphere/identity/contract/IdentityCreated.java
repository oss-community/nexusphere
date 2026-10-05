package com.nexusphere.identity.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record IdentityCreated(UUID eventId, Instant occurredAt, IdentityId identity, String type, String displayName) implements DomainEvent {

    public IdentityCreated(Instant occurredAt, IdentityId identity, String type, String displayName) {
        this(UUID.randomUUID(), occurredAt, identity, type, displayName);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.empty();
    }
}
