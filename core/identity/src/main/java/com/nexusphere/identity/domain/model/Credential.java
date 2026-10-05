package com.nexusphere.identity.domain.model;

import com.nexusphere.shared.id.IdentityId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Credential(UUID id, IdentityId identityId, String secretHash, Instant createdAt) {

    public Credential {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(secretHash, "secretHash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
