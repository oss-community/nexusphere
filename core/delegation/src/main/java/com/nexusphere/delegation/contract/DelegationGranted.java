package com.nexusphere.delegation.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record DelegationGranted(UUID eventId, Instant occurredAt, UUID delegationId, NetworkId delegationNetworkId,
                                PrincipalId delegatorPrincipalId, PrincipalId delegatePrincipalId,
                                Set<String> actions) implements DomainEvent {

    public DelegationGranted(Instant occurredAt, UUID delegationId, NetworkId delegationNetworkId,
                             PrincipalId delegatorPrincipalId, PrincipalId delegatePrincipalId, Set<String> actions) {
        this(UUID.randomUUID(), occurredAt, delegationId, delegationNetworkId, delegatorPrincipalId,
                delegatePrincipalId, actions);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(delegationNetworkId);
    }
}
