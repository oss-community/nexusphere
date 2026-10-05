package com.nexusphere.transaction.domain.model;

import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

import java.util.Objects;

public record TransactionParty(OrganizationId organizationId, NetworkId networkId) {

    public TransactionParty {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(networkId, "networkId must not be null");
    }

    public boolean represents(Participant participant) {
        return participant.networkId().equals(networkId)
                && (organizationId.equals(participant.organizationId()) || participant.networkAdministrator());
    }
}
