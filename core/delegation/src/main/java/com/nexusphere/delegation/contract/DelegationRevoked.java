package com.nexusphere.delegation.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record DelegationRevoked(UUID eventId, Instant occurredAt, UUID delegationId, NetworkId delegationNetworkId,
                                PrincipalId delegatorPrincipalId,
                                PrincipalId delegatePrincipalId) implements DomainEvent {

    public DelegationRevoked(Instant occurredAt, UUID delegationId, NetworkId delegationNetworkId,
                             PrincipalId delegatorPrincipalId, PrincipalId delegatePrincipalId) {
        this(UUID.randomUUID(), occurredAt, delegationId, delegationNetworkId, delegatorPrincipalId,
                delegatePrincipalId);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(delegationNetworkId);
    }
}
