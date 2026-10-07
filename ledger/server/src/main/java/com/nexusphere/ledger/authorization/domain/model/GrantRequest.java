package com.nexusphere.ledger.authorization.domain.model;

import java.time.Instant;
import java.util.List;

public record GrantRequest(
        String principalId,
        String agentId,
        List<String> actions,
        List<String> targets,
        Instant notBefore,
        Instant expiresAt,
        Long maxUses,
        String reason) {
}
