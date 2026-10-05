package com.nexusphere.authorization.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record AuthorizationDenied(UUID eventId, Instant occurredAt, AuthorizationDecision decision)
        implements DomainEvent {

    public AuthorizationDenied(AuthorizationDecision decision) {
        this(UUID.randomUUID(), decision.decidedAt(), decision);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(decision.networkId());
    }
}
