package com.nexusphere.ledger.approval.api.rest;

import com.nexusphere.ledger.approval.application.Approval;
import com.nexusphere.ledger.server.web.LedgerException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record ApprovalResponse(UUID id, String agentId, String principalId, String action, String target, String inputHash,
                        String policyHash, List<String> policies, String approver, String status,
                        Instant requestedAt, Instant expiresAt, Instant resolvedAt, String method, String reason,
                        UUID evidenceId, boolean used) {

    static ApprovalResponse of(Approval a) {
        return new ApprovalResponse(a.id(), a.agentId(), a.principalId(), a.action(), a.target(), a.inputHash(),
                a.policyHash(), a.policies(), a.approver(), a.status().name(), a.requestedAt(), a.expiresAt(),
                a.resolvedAt(), a.method(), a.reason(), a.evidenceId(),
                a.closedAt() != null && a.status() == Approval.Status.APPROVED);
    }

    record ApprovalPage(List<ApprovalResponse> items) {
    }

    record AnswerRequest(String reason) {
    }

    static Approval.Status status(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Approval.Status.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw LedgerException.invalid("The query has invalid parameters.",
                    Map.of("status", "must be one of PENDING, APPROVED, DENIED, EXPIRED"));
        }
    }
}
