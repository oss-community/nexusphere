package com.nexusphere.membership.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record NetworkAccessDenied(UUID eventId, Instant occurredAt, IdentityId identityId, NetworkId homeNetworkId,
                                  NetworkId requestedNetworkId, String reason) implements DomainEvent {

    public NetworkAccessDenied(Instant occurredAt, IdentityId identityId, NetworkId homeNetworkId,
                               NetworkId requestedNetworkId, String reason) {
        this(UUID.randomUUID(), occurredAt, identityId, homeNetworkId, requestedNetworkId, reason);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(homeNetworkId);
    }
}
