package com.nexusphere.audit.contract;

import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditRecord(UUID id, UUID sourceEventId, String eventType, Instant occurredAt, NetworkId networkId,
                          PrincipalId principalId, IdentityId identityId, OrganizationId accountableOrganizationId,
                          String action, String resourceType, String resourceId, UUID federationId,
                          UUID delegationId, UUID agreementId, UUID transactionId, UUID decisionId, String result,
                          String reason, String correlationId, UUID causationId, Map<String, String> metadata) {

    public AuditRecord {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
