package com.nexusphere.identity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "credential")
class CredentialEntity {

    @Id
    private UUID id;

    @Column(name = "identity_id", nullable = false)
    private UUID identityId;

    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected CredentialEntity() {
    }

    CredentialEntity(UUID id, UUID identityId, String secretHash, Instant createdAt, Instant expiresAt,
                     Instant revokedAt) {
        this.id = id;
        this.identityId = identityId;
        this.secretHash = secretHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }

    UUID getId() {
        return id;
    }

    UUID getIdentityId() {
        return identityId;
    }

    String getSecretHash() {
        return secretHash;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getRevokedAt() {
        return revokedAt;
    }
}
