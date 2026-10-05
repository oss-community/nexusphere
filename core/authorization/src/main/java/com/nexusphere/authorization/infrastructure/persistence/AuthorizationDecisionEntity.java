package com.nexusphere.authorization.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "`authorization`", name = "authorization_decision")
class AuthorizationDecisionEntity {

    @Id
    private UUID id;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(name = "network_id", nullable = false)
    private UUID networkId;

    @Column(name = "target_network_id", nullable = false)
    private UUID targetNetworkId;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(name = "resource_type", length = 60)
    private String resourceType;

    @Column(name = "resource_id", length = 200)
    private String resourceId;

    @Column(nullable = false)
    private boolean allowed;

    @Column(nullable = false, length = 60)
    private String reason;

    @Column(name = "matched_role", length = 40)
    private String matchedRole;

    @Column(name = "delegation_id")
    private UUID delegationId;

    @Column(name = "federation_id")
    private UUID federationId;

    @Column(name = "trust_relationship_id")
    private UUID trustRelationshipId;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected AuthorizationDecisionEntity() {
    }

    AuthorizationDecisionEntity(UUID id, UUID principalId, UUID networkId, UUID targetNetworkId, String action,
                                String resourceType, String resourceId, boolean allowed, String reason,
                                String matchedRole, UUID delegationId, UUID federationId, UUID trustRelationshipId,
                                Instant decidedAt) {
        this.id = id;
        this.principalId = principalId;
        this.networkId = networkId;
        this.targetNetworkId = targetNetworkId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.allowed = allowed;
        this.reason = reason;
        this.matchedRole = matchedRole;
        this.delegationId = delegationId;
        this.federationId = federationId;
        this.trustRelationshipId = trustRelationshipId;
        this.decidedAt = decidedAt;
    }

    UUID getId() {
        return id;
    }

    UUID getPrincipalId() {
        return principalId;
    }

    UUID getNetworkId() {
        return networkId;
    }

    UUID getTargetNetworkId() {
        return targetNetworkId;
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

    boolean isAllowed() {
        return allowed;
    }

    String getReason() {
        return reason;
    }

    String getMatchedRole() {
        return matchedRole;
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

    Instant getDecidedAt() {
        return decidedAt;
    }
}
