package com.nexusphere.transaction.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record TransactionChanged(UUID eventId, Instant occurredAt, UUID transactionId, NetworkId transactionNetworkId,
                                 NetworkId providerNetworkId, UUID agreementId, String change, String status,
                                 String reason, PrincipalId principalId, NetworkId principalNetworkId)
        implements DomainEvent {

    public TransactionChanged(Instant occurredAt, UUID transactionId, NetworkId transactionNetworkId,
                              NetworkId providerNetworkId, UUID agreementId, String change, String status,
                              String reason, PrincipalId principalId, NetworkId principalNetworkId) {
        this(UUID.randomUUID(), occurredAt, transactionId, transactionNetworkId, providerNetworkId, agreementId,
                change, status, reason, principalId, principalNetworkId);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(transactionNetworkId);
    }
}
