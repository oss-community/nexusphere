package com.nexusphere.ledger.approval.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Approval(UUID id, String agentId, String principalId, String action, String target, String inputHash,
                       String policyHash, List<String> policies, String approver, Status status, Instant requestedAt,
                       Instant expiresAt, Instant resolvedAt, String resolvedBy, String method, String reason,
                       UUID evidenceId, Instant closedAt) {

    public enum Status {
        PENDING,
        APPROVED,
        DENIED,
        EXPIRED
    }

    public String describe() {
        return target == null ? action : action + " on " + target;
    }
}
