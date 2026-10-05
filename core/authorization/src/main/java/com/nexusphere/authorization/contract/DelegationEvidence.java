package com.nexusphere.authorization.contract;

import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record DelegationEvidence(UUID id, NetworkId networkId, PrincipalId delegatorPrincipalId,
                                 PrincipalId delegatePrincipalId, Set<String> actions, Set<String> capabilityTypes,
                                 Set<NetworkId> networks, Set<String> resourceTypes, Instant validFrom,
                                 Instant validUntil, String status, Instant grantedAt) {

    public DelegationEvidence {
        actions = Set.copyOf(actions);
        capabilityTypes = Set.copyOf(capabilityTypes);
        networks = Set.copyOf(networks);
        resourceTypes = Set.copyOf(resourceTypes);
    }
}
