package com.nexusphere.ledger.authorization.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Mandate(
        UUID id,
        long statusIndex,
        UUID grantId,
        String agentId,
        String principalId,
        String audience,
        Instant issuedAt,
        Instant expiresAt,
        String token,
        Instant revokedAt,
        String revokeReason) {

    public String status(Instant now) {
        if (revokedAt != null) {
            return "REVOKED";
        }
        return now.isBefore(expiresAt) ? "ACTIVE" : "EXPIRED";
    }
}
