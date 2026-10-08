package com.nexusphere.integration.domain.model;

import java.time.Instant;
import java.util.UUID;

public record LedgerGrant(UUID delegationId, String grantId, String agentId, Instant createdAt, Instant revokedAt) {
}
