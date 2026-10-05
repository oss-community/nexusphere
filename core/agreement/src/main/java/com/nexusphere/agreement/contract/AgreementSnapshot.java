package com.nexusphere.agreement.contract;

import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

import java.util.UUID;

public record AgreementSnapshot(UUID id, NetworkId networkId, String type, String status, int currentVersion,
                                CapabilityId capabilityId, NetworkId capabilityNetworkId, String capabilityTypeCode,
                                OrganizationId proposerOrganizationId, NetworkId proposerNetworkId,
                                OrganizationId counterpartyOrganizationId, NetworkId counterpartyNetworkId) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }

    public boolean involves(NetworkId network) {
        return proposerNetworkId.equals(network) || counterpartyNetworkId.equals(network);
    }
}
