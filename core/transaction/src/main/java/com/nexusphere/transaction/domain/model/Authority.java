package com.nexusphere.transaction.domain.model;

import java.util.UUID;

public record Authority(UUID decisionId, UUID delegationId, UUID federationId, UUID trustRelationshipId) {
}
