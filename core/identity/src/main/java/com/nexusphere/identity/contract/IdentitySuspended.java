package com.nexusphere.identity.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record IdentitySuspended(UUID eventId, Instant occurredAt, IdentityId identity) implements DomainEvent {

    public IdentitySuspended(Instant occurredAt, IdentityId identity) {
        this(UUID.randomUUID(), occurredAt, identity);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.empty();
    }
}
