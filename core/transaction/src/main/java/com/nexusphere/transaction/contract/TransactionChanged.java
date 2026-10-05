package com.nexusphere.transaction.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record TransactionChanged(UUID eventId, Instant occurredAt, UUID transactionId, NetworkId transactionNetworkId,
                                 NetworkId providerNetworkId, UUID agreementId, CapabilityId capabilityId,
                                 String change, String status, String reason, PrincipalId principalId,
                                 NetworkId principalNetworkId, OrganizationId accountableOrganizationId,
                                 UUID decisionId, UUID delegationId, UUID federationId) implements DomainEvent {

    public TransactionChanged(Instant occurredAt, UUID transactionId, NetworkId transactionNetworkId,
                              NetworkId providerNetworkId, UUID agreementId, CapabilityId capabilityId,
                              String change, String status, String reason, PrincipalId principalId,
                              NetworkId principalNetworkId, OrganizationId accountableOrganizationId,
                              UUID decisionId, UUID delegationId, UUID federationId) {
        this(UUID.randomUUID(), occurredAt, transactionId, transactionNetworkId, providerNetworkId, agreementId,
                capabilityId, change, status, reason, principalId, principalNetworkId, accountableOrganizationId,
                decisionId, delegationId, federationId);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(transactionNetworkId);
    }
}
