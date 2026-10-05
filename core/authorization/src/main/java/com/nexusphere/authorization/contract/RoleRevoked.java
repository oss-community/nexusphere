package com.nexusphere.authorization.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record RoleRevoked(UUID eventId, Instant occurredAt, NetworkId network, UUID assignmentId, PrincipalId principal,
                          String role, PrincipalId actor) implements DomainEvent {

    public RoleRevoked(Instant occurredAt, NetworkId network, UUID assignmentId, PrincipalId principal, String role,
                       PrincipalId actor) {
        this(UUID.randomUUID(), occurredAt, network, assignmentId, principal, role, actor);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(network);
    }
}
