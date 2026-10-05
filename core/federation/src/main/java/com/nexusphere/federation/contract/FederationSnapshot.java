package com.nexusphere.federation.contract;

import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record FederationSnapshot(UUID id, NetworkId proposerNetworkId, NetworkId partnerNetworkId, Set<String> scopes,
                                 String status, Instant effectiveUntil) {

    public boolean covers(String scope) {
        return scopes.contains(scope);
    }
}
