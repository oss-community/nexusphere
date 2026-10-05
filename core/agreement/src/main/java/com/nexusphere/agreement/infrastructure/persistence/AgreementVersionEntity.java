package com.nexusphere.agreement.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "agreement", name = "agreement_version")
class AgreementVersionEntity {

    @Id
    private UUID id;

    @Column(name = "agreement_id", nullable = false)
    private UUID agreementId;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false, columnDefinition = "text")
    private String terms;

    @Column(nullable = false, columnDefinition = "text")
    private String changes;

    @Column(name = "on_behalf_organization_id", nullable = false)
    private UUID onBehalfOrganizationId;

    @Column(name = "on_behalf_network_id", nullable = false)
    private UUID onBehalfNetworkId;

    @Column(name = "proposed_by", nullable = false)
    private UUID proposedBy;

    @Column(name = "proposed_by_identity", nullable = false)
    private UUID proposedByIdentity;

    @Column(name = "delegation_id")
    private UUID delegationId;

    @Column(name = "decision_id")
    private UUID decisionId;

    @Column(name = "federation_id")
    private UUID federationId;

    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt;

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "acceptance_decision_id")
    private UUID acceptanceDecisionId;

    @Column(nullable = false)
    private boolean superseded;

    protected AgreementVersionEntity() {
    }

    AgreementVersionEntity(UUID id, UUID agreementId, int number, String terms, String changes,
                           UUID onBehalfOrganizationId, UUID onBehalfNetworkId, UUID proposedBy,
                           UUID proposedByIdentity, UUID delegationId, UUID decisionId, UUID federationId,
                           Instant proposedAt, UUID acceptedBy, Instant acceptedAt, UUID acceptanceDecisionId,
                           boolean superseded) {
        this.id = id;
        this.agreementId = agreementId;
        this.number = number;
        this.terms = terms;
        this.changes = changes;
        this.onBehalfOrganizationId = onBehalfOrganizationId;
        this.onBehalfNetworkId = onBehalfNetworkId;
        this.proposedBy = proposedBy;
        this.proposedByIdentity = proposedByIdentity;
        this.delegationId = delegationId;
        this.decisionId = decisionId;
        this.federationId = federationId;
        this.proposedAt = proposedAt;
        this.acceptedBy = acceptedBy;
        this.acceptedAt = acceptedAt;
        this.acceptanceDecisionId = acceptanceDecisionId;
        this.superseded = superseded;
    }

    UUID getId() {
        return id;
    }

    UUID getAgreementId() {
        return agreementId;
    }

    int getNumber() {
        return number;
    }

    String getTerms() {
        return terms;
    }

    String getChanges() {
        return changes;
    }

    UUID getOnBehalfOrganizationId() {
        return onBehalfOrganizationId;
    }

    UUID getOnBehalfNetworkId() {
        return onBehalfNetworkId;
    }

    UUID getProposedBy() {
        return proposedBy;
    }

    UUID getProposedByIdentity() {
        return proposedByIdentity;
    }

    UUID getDelegationId() {
        return delegationId;
    }

    UUID getDecisionId() {
        return decisionId;
    }

    UUID getFederationId() {
        return federationId;
    }

    Instant getProposedAt() {
        return proposedAt;
    }

    UUID getAcceptedBy() {
        return acceptedBy;
    }

    Instant getAcceptedAt() {
        return acceptedAt;
    }

    UUID getAcceptanceDecisionId() {
        return acceptanceDecisionId;
    }

    boolean isSuperseded() {
        return superseded;
    }
}
