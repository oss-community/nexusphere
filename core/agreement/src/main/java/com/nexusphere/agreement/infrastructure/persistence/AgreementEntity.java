package com.nexusphere.agreement.infrastructure.persistence;

import com.nexusphere.agreement.domain.model.AgreementStatus;
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
@Table(schema = "agreement", name = "agreement")
class AgreementEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 60)
    private String type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "capability_id", nullable = false)
    private UUID capabilityId;

    @Column(name = "capability_network_id", nullable = false)
    private UUID capabilityNetworkId;

    @Column(name = "capability_type_code", nullable = false, length = 120)
    private String capabilityTypeCode;

    @Column(name = "proposer_organization_id", nullable = false)
    private UUID proposerOrganizationId;

    @Column(name = "proposer_network_id", nullable = false)
    private UUID proposerNetworkId;

    @Column(name = "counterparty_organization_id", nullable = false)
    private UUID counterpartyOrganizationId;

    @Column(name = "counterparty_network_id", nullable = false)
    private UUID counterpartyNetworkId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgreementStatus status;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closing_reason", columnDefinition = "text")
    private String closingReason;

    @Version
    private Long version;

    protected AgreementEntity() {
    }

    AgreementEntity(UUID id, String type, String title, UUID capabilityId, UUID capabilityNetworkId,
                    String capabilityTypeCode, UUID proposerOrganizationId, UUID proposerNetworkId,
                    UUID counterpartyOrganizationId, UUID counterpartyNetworkId, AgreementStatus status,
                    int currentVersion, Instant createdAt, Instant activatedAt, Instant closedAt, String closingReason,
                    Long version) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.capabilityId = capabilityId;
        this.capabilityNetworkId = capabilityNetworkId;
        this.capabilityTypeCode = capabilityTypeCode;
        this.proposerOrganizationId = proposerOrganizationId;
        this.proposerNetworkId = proposerNetworkId;
        this.counterpartyOrganizationId = counterpartyOrganizationId;
        this.counterpartyNetworkId = counterpartyNetworkId;
        this.status = status;
        this.currentVersion = currentVersion;
        this.createdAt = createdAt;
        this.activatedAt = activatedAt;
        this.closedAt = closedAt;
        this.closingReason = closingReason;
        this.version = version;
    }

    UUID getId() {
        return id;
    }

    String getType() {
        return type;
    }

    String getTitle() {
        return title;
    }

    UUID getCapabilityId() {
        return capabilityId;
    }

    UUID getCapabilityNetworkId() {
        return capabilityNetworkId;
    }

    String getCapabilityTypeCode() {
        return capabilityTypeCode;
    }

    UUID getProposerOrganizationId() {
        return proposerOrganizationId;
    }

    UUID getProposerNetworkId() {
        return proposerNetworkId;
    }

    UUID getCounterpartyOrganizationId() {
        return counterpartyOrganizationId;
    }

    UUID getCounterpartyNetworkId() {
        return counterpartyNetworkId;
    }

    AgreementStatus getStatus() {
        return status;
    }

    int getCurrentVersion() {
        return currentVersion;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getActivatedAt() {
        return activatedAt;
    }

    Instant getClosedAt() {
        return closedAt;
    }

    String getClosingReason() {
        return closingReason;
    }

    Long getVersion() {
        return version;
    }
}
