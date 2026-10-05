package com.nexusphere.identity.domain.model;

import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.IdentityId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Credential(UUID id, IdentityId identityId, String secretHash, Instant createdAt, Instant expiresAt,
                         Instant revokedAt) {

    public Credential {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(secretHash, "secretHash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public static Credential issue(UUID id, IdentityId identityId, String secretHash, Instant now, Instant expiresAt) {
        if (!expiresAt.isAfter(now)) {
            throw new ValidationException("CREDENTIAL_EXPIRY_IN_PAST", "A credential must expire in the future");
        }
        return new Credential(id, identityId, secretHash, now, expiresAt, null);
    }

    public boolean usableAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public CredentialStatus status(Instant now) {
        if (revokedAt != null) {
            return CredentialStatus.REVOKED;
        }
        return now.isBefore(expiresAt) ? CredentialStatus.ACTIVE : CredentialStatus.EXPIRED;
    }

    public Credential revoke(Instant now) {
        if (revokedAt != null) {
            throw new ConflictException("CREDENTIAL_ALREADY_REVOKED", "Credential " + id + " is already revoked");
        }
        return new Credential(id, identityId, secretHash, createdAt, expiresAt, now);
    }
}
