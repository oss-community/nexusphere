package com.nexusphere.identity.domain.model;

import com.nexusphere.identity.contract.IdentityActivated;
import com.nexusphere.identity.contract.IdentityCreated;
import com.nexusphere.identity.contract.IdentitySuspended;
import com.nexusphere.shared.domain.AggregateRoot;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.IdentityId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class Identity extends AggregateRoot<IdentityId> {

    public static final int DISPLAY_NAME_MAX_LENGTH = 120;
    public static final int DESCRIPTOR_MAX_LENGTH = 100;

    private final IdentityId id;
    private final IdentityType type;
    private final String displayName;
    private final Ownership ownership;
    private final String agentProvider;
    private final String agentModel;
    private IdentityStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    private Identity(IdentityId id, IdentityType type, String displayName, Ownership ownership, String agentProvider,
                     String agentModel, IdentityStatus status, Instant createdAt, Instant updatedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.displayName = displayName;
        this.ownership = ownership;
        this.agentProvider = agentProvider;
        this.agentModel = agentModel;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    public static Identity create(IdentityId id, IdentityType type, String displayName, Ownership ownership,
                                  String agentProvider, String agentModel, Instant now) {
        Objects.requireNonNull(type, "type must not be null");
        if (type.requiresOwningOrganization() && ownership == null) {
            throw new ValidationException("OWNING_ORGANIZATION_REQUIRED",
                    "An identity of type " + type + " must be owned by an organization");
        }
        if (!type.requiresOwningOrganization() && ownership != null) {
            throw new ValidationException("OWNING_ORGANIZATION_NOT_ALLOWED",
                    "An identity of type " + type + " cannot be owned by an organization");
        }
        if (type != IdentityType.AGENT && (agentProvider != null || agentModel != null)) {
            throw new ValidationException("AGENT_DESCRIPTOR_NOT_ALLOWED",
                    "Agent provider and model apply only to identities of type AGENT");
        }
        Identity identity = new Identity(id, type, normalize(displayName, DISPLAY_NAME_MAX_LENGTH, "INVALID_DISPLAY_NAME", true),
                ownership, normalize(agentProvider, DESCRIPTOR_MAX_LENGTH, "INVALID_AGENT_PROVIDER", false),
                normalize(agentModel, DESCRIPTOR_MAX_LENGTH, "INVALID_AGENT_MODEL", false),
                IdentityStatus.ACTIVE, now, now, null);
        identity.registerEvent(new IdentityCreated(now, id, type.name(), identity.displayName));
        return identity;
    }

    public static Identity restore(IdentityId id, IdentityType type, String displayName, Ownership ownership,
                                   String agentProvider, String agentModel, IdentityStatus status,
                                   Instant createdAt, Instant updatedAt, Long version) {
        return new Identity(id, type, displayName, ownership, agentProvider, agentModel, status, createdAt, updatedAt,
                version);
    }

    public void suspend(Instant now) {
        if (status == IdentityStatus.SUSPENDED) {
            throw new ConflictException("IDENTITY_ALREADY_SUSPENDED", "Identity " + id + " is already suspended");
        }
        status = IdentityStatus.SUSPENDED;
        updatedAt = now;
        registerEvent(new IdentitySuspended(now, id));
    }

    public void activate(Instant now) {
        if (status == IdentityStatus.ACTIVE) {
            throw new ConflictException("IDENTITY_ALREADY_ACTIVE", "Identity " + id + " is already active");
        }
        status = IdentityStatus.ACTIVE;
        updatedAt = now;
        registerEvent(new IdentityActivated(now, id));
    }

    public boolean isActive() {
        return status == IdentityStatus.ACTIVE;
    }

    private static String normalize(String value, int maxLength, String code, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new ValidationException(code, "Value is required");
            }
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new ValidationException(code, "Value must have at most " + maxLength + " characters");
        }
        return stripped;
    }

    @Override
    public IdentityId id() {
        return id;
    }

    public IdentityType type() {
        return type;
    }

    public String displayName() {
        return displayName;
    }

    public Optional<Ownership> ownership() {
        return Optional.ofNullable(ownership);
    }

    public String agentProvider() {
        return agentProvider;
    }

    public String agentModel() {
        return agentModel;
    }

    public IdentityStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long version() {
        return version;
    }
}
