package com.nexusphere.ledger.authorization.domain.model;

import com.nexusphere.ledger.chain.GrantTerms;

import java.time.Instant;

public record Grant(
        long seq,
        GrantTerms terms,
        long uses,
        GrantState state,
        String reason,
        Instant revokedAt,
        String revokeReason) {

    public GrantStatus status(Instant now) {
        if (state == GrantState.REVOKED) {
            return GrantStatus.REVOKED;
        }
        if (!now.isBefore(terms.expiresAt())) {
            return GrantStatus.EXPIRED;
        }
        if (terms.maxUses() != null && uses >= terms.maxUses()) {
            return GrantStatus.EXHAUSTED;
        }
        if (terms.notBefore() != null && now.isBefore(terms.notBefore())) {
            return GrantStatus.NOT_YET_VALID;
        }
        return GrantStatus.ACTIVE;
    }
}
