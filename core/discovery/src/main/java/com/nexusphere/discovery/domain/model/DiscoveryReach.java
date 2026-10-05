package com.nexusphere.discovery.domain.model;

import com.nexusphere.shared.id.NetworkId;

import java.util.UUID;

public record DiscoveryReach(NetworkId networkId, String networkName, boolean federated, UUID federationId,
                             UUID trustRelationshipId, UUID decisionId) {
}
