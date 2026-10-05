package com.nexusphere.audit.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(schema = "audit", name = "audit_event")
class AuditEventEntity {

    @Id
    private UUID id;

    @Column(name = "recorded_order", insertable = false, updatable = false)
    private Long recordedOrder;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "event_type", nullable = false, length = 120, updatable = false)
    private String eventType;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "network_id", nullable = false, updatable = false)
    private UUID networkId;

    @Column(name = "principal_id", updatable = false)
    private UUID principalId;

    @Column(name = "identity_id", updatable = false)
    private UUID identityId;

    @Column(name = "accountable_organization_id", updatable = false)
    private UUID accountableOrganizationId;

    @Column(nullable = false, length = 120, updatable = false)
    private String action;

    @Column(name = "resource_type", length = 60, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", length = 200, updatable = false)
    private String resourceId;

    @Column(name = "federation_id", updatable = false)
    private UUID federationId;

    @Column(name = "delegation_id", updatable = false)
    private UUID delegationId;

    @Column(name = "agreement_id", updatable = false)
    private UUID agreementId;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(name = "decision_id", updatable = false)
    private UUID decisionId;

    @Column(nullable = false, length = 20, updatable = false)
    private String result;

    @Column(length = 200, updatable = false)
    private String reason;

    @Column(name = "correlation_id", nullable = false, length = 128, updatable = false)
    private String correlationId;

    @Column(name = "causation_id", updatable = false)
    private UUID causationId;

    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String metadata;

    protected AuditEventEntity() {
    }

    AuditEventEntity(UUID id, UUID sourceEventId, String eventType, Instant occurredAt, Instant recordedAt,
                     UUID networkId, UUID principalId, UUID identityId, UUID accountableOrganizationId, String action,
                     String resourceType, String resourceId, UUID federationId, UUID delegationId, UUID agreementId,
                     UUID transactionId, UUID decisionId, String result, String reason, String correlationId,
                     UUID causationId, String metadata) {
        this.id = id;
        this.sourceEventId = sourceEventId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.networkId = networkId;
        this.principalId = principalId;
        this.identityId = identityId;
        this.accountableOrganizationId = accountableOrganizationId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.federationId = federationId;
        this.delegationId = delegationId;
        this.agreementId = agreementId;
        this.transactionId = transactionId;
        this.decisionId = decisionId;
        this.result = result;
        this.reason = reason;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.metadata = metadata;
    }

    UUID getId() {
        return id;
    }

    UUID getSourceEventId() {
        return sourceEventId;
    }

    String getEventType() {
        return eventType;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    Instant getRecordedAt() {
        return recordedAt;
    }

    UUID getNetworkId() {
        return networkId;
    }

    UUID getPrincipalId() {
        return principalId;
    }

    UUID getIdentityId() {
        return identityId;
    }

    UUID getAccountableOrganizationId() {
        return accountableOrganizationId;
    }

    String getAction() {
        return action;
    }

    String getResourceType() {
        return resourceType;
    }

    String getResourceId() {
        return resourceId;
    }

    UUID getFederationId() {
        return federationId;
    }

    UUID getDelegationId() {
        return delegationId;
    }

    UUID getAgreementId() {
        return agreementId;
    }

    UUID getTransactionId() {
        return transactionId;
    }

    UUID getDecisionId() {
        return decisionId;
    }

    String getResult() {
        return result;
    }

    String getReason() {
        return reason;
    }

    String getCorrelationId() {
        return correlationId;
    }

    UUID getCausationId() {
        return causationId;
    }

    String getMetadata() {
        return metadata;
    }
}
