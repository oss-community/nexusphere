package com.nexusphere.transaction.infrastructure.persistence;

import com.nexusphere.transaction.domain.model.TransactionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "transaction", name = "transaction")
class TransactionEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 60)
    private String type;

    @Column(name = "agreement_id", nullable = false)
    private UUID agreementId;

    @Column(name = "agreement_version", nullable = false)
    private int agreementVersion;

    @Column(name = "capability_id", nullable = false)
    private UUID capabilityId;

    @Column(name = "capability_network_id", nullable = false)
    private UUID capabilityNetworkId;

    @Column(name = "requester_organization_id", nullable = false)
    private UUID requesterOrganizationId;

    @Column(name = "requester_network_id", nullable = false)
    private UUID requesterNetworkId;

    @Column(name = "provider_organization_id", nullable = false)
    private UUID providerOrganizationId;

    @Column(name = "provider_network_id", nullable = false)
    private UUID providerNetworkId;

    @Column(name = "initiating_principal_id", nullable = false)
    private UUID initiatingPrincipalId;

    @Column(name = "initiating_identity_id", nullable = false)
    private UUID initiatingIdentityId;

    @Column(name = "decision_id")
    private UUID decisionId;

    @Column(name = "delegation_id")
    private UUID delegationId;

    @Column(name = "federation_id")
    private UUID federationId;

    @Column(name = "trust_relationship_id")
    private UUID trustRelationshipId;

    @Column(nullable = false, columnDefinition = "text")
    private String metadata;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(length = 200)
    private String reason;

    @Column(columnDefinition = "text")
    private String result;

    @Column(name = "executor_principal_id")
    private UUID executorPrincipalId;

    @Column(name = "executor_identity_id")
    private UUID executorIdentityId;

    @Column(name = "execution_decision_id")
    private UUID executionDecisionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    private Long version;

    protected TransactionEntity() {
    }

    TransactionEntity(UUID id, String type, UUID agreementId, int agreementVersion, UUID capabilityId,
                      UUID capabilityNetworkId, UUID requesterOrganizationId, UUID requesterNetworkId,
                      UUID providerOrganizationId, UUID providerNetworkId, UUID initiatingPrincipalId,
                      UUID initiatingIdentityId, UUID decisionId, UUID delegationId, UUID federationId,
                      UUID trustRelationshipId, String metadata, TransactionStatus status, String reason, String result,
                      UUID executorPrincipalId, UUID executorIdentityId, UUID executionDecisionId, Instant createdAt,
                      Instant authorizedAt, Instant startedAt, Instant finishedAt, Long version) {
        this.id = id;
        this.type = type;
        this.agreementId = agreementId;
        this.agreementVersion = agreementVersion;
        this.capabilityId = capabilityId;
        this.capabilityNetworkId = capabilityNetworkId;
        this.requesterOrganizationId = requesterOrganizationId;
        this.requesterNetworkId = requesterNetworkId;
        this.providerOrganizationId = providerOrganizationId;
        this.providerNetworkId = providerNetworkId;
        this.initiatingPrincipalId = initiatingPrincipalId;
        this.initiatingIdentityId = initiatingIdentityId;
        this.decisionId = decisionId;
        this.delegationId = delegationId;
        this.federationId = federationId;
        this.trustRelationshipId = trustRelationshipId;
        this.metadata = metadata;
        this.status = status;
        this.reason = reason;
        this.result = result;
        this.executorPrincipalId = executorPrincipalId;
        this.executorIdentityId = executorIdentityId;
        this.executionDecisionId = executionDecisionId;
        this.createdAt = createdAt;
        this.authorizedAt = authorizedAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.version = version;
    }

    UUID getId() {
        return id;
    }

    String getType() {
        return type;
    }

    UUID getAgreementId() {
        return agreementId;
    }

    int getAgreementVersion() {
        return agreementVersion;
    }

    UUID getCapabilityId() {
        return capabilityId;
    }

    UUID getCapabilityNetworkId() {
        return capabilityNetworkId;
    }

    UUID getRequesterOrganizationId() {
        return requesterOrganizationId;
    }

    UUID getRequesterNetworkId() {
        return requesterNetworkId;
    }

    UUID getProviderOrganizationId() {
        return providerOrganizationId;
    }

    UUID getProviderNetworkId() {
        return providerNetworkId;
    }

    UUID getInitiatingPrincipalId() {
        return initiatingPrincipalId;
    }

    UUID getInitiatingIdentityId() {
        return initiatingIdentityId;
    }

    UUID getDecisionId() {
        return decisionId;
    }

    UUID getDelegationId() {
        return delegationId;
    }

    UUID getFederationId() {
        return federationId;
    }

    UUID getTrustRelationshipId() {
        return trustRelationshipId;
    }

    String getMetadata() {
        return metadata;
    }

    TransactionStatus getStatus() {
        return status;
    }

    String getReason() {
        return reason;
    }

    String getResult() {
        return result;
    }

    UUID getExecutorPrincipalId() {
        return executorPrincipalId;
    }

    UUID getExecutorIdentityId() {
        return executorIdentityId;
    }

    UUID getExecutionDecisionId() {
        return executionDecisionId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getAuthorizedAt() {
        return authorizedAt;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    Instant getFinishedAt() {
        return finishedAt;
    }

    Long getVersion() {
        return version;
    }
}
