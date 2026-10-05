package com.nexusphere.agreement.domain.model;

import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

import java.util.Objects;

public record AgreementParty(OrganizationId organizationId, NetworkId networkId) {

    public AgreementParty {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(networkId, "networkId must not be null");
    }

    public boolean represents(Actor actor) {
        return actor.networkId().equals(networkId)
                && (organizationId.equals(actor.organizationId()) || actor.networkAdministrator());
    }
}
