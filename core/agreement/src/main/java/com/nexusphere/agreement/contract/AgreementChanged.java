package com.nexusphere.agreement.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record AgreementChanged(UUID eventId, Instant occurredAt, UUID agreementId, NetworkId agreementNetworkId,
                               NetworkId counterpartyNetworkId, String change, String status, int version,
                               PrincipalId principalId, NetworkId principalNetworkId,
                               OrganizationId accountableOrganizationId, UUID decisionId, UUID delegationId,
                               UUID federationId) implements DomainEvent {

    public AgreementChanged(Instant occurredAt, UUID agreementId, NetworkId agreementNetworkId,
                            NetworkId counterpartyNetworkId, String change, String status, int version,
                            PrincipalId principalId, NetworkId principalNetworkId,
                            OrganizationId accountableOrganizationId, UUID decisionId, UUID delegationId,
                            UUID federationId) {
        this(UUID.randomUUID(), occurredAt, agreementId, agreementNetworkId, counterpartyNetworkId, change, status,
                version, principalId, principalNetworkId, accountableOrganizationId, decisionId, delegationId,
                federationId);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(agreementNetworkId);
    }
}
