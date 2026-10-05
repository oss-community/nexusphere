package com.nexusphere.authorization.contract;

import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.UUID;

public record AuthorizationDecision(UUID id, PrincipalId principalId, NetworkId networkId, NetworkId targetNetworkId,
                                    String action, String resourceType, String resourceId, boolean allowed,
                                    String reason, String matchedRole, UUID delegationId, UUID federationId,
                                    UUID trustRelationshipId, Instant decidedAt) {

    public String result() {
        return allowed ? "ALLOW" : "DENY";
    }
}
