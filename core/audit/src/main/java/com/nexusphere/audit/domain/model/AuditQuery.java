package com.nexusphere.audit.domain.model;

import com.nexusphere.shared.id.PrincipalId;

import java.util.UUID;

public record AuditQuery(UUID transactionId, UUID agreementId, UUID delegationId, UUID decisionId,
                         PrincipalId principalId, String correlationId, String result, String resourceType,
                         String resourceId) {

    public static AuditQuery none() {
        return new AuditQuery(null, null, null, null, null, null, null, null, null);
    }
}
