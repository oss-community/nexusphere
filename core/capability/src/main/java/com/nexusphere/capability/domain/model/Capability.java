package com.nexusphere.capability.domain.model;

import com.nexusphere.capability.contract.CapabilityPublished;
import com.nexusphere.capability.contract.CapabilityRegistered;
import com.nexusphere.capability.contract.CapabilityVisibilityChanged;
import com.nexusphere.capability.contract.CapabilityWithdrawn;
import com.nexusphere.shared.domain.AggregateRoot;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class Capability extends AggregateRoot<CapabilityId> {

    public static final int NAME_MAX_LENGTH = 200;
    public static final int DESCRIPTION_MAX_LENGTH = 2000;

    private final CapabilityId id;
    private final NetworkId networkId;
    private final CapabilityOwner owner;
    private final OrganizationId accountableOrganizationId;
    private final String name;
    private final String description;
    private final UUID typeId;
    private final String typeCode;
    private final int typeVersion;
    private final Map<String, Object> specification;
    private CapabilityVisibility visibility;
    private CapabilityStatus status;
    private final Instant createdAt;
    private Instant publishedAt;
    private Instant withdrawnAt;
    private final Long version;

    private Capability(CapabilityId id, NetworkId networkId, CapabilityOwner owner,
                       OrganizationId accountableOrganizationId, String name, String description, UUID typeId,
                       String typeCode, int typeVersion, Map<String, Object> specification,
                       CapabilityVisibility visibility, CapabilityStatus status, Instant createdAt,
                       Instant publishedAt, Instant withdrawnAt, Long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.networkId = Objects.requireNonNull(networkId, "networkId must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.accountableOrganizationId = accountableOrganizationId;
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
        this.typeId = Objects.requireNonNull(typeId, "typeId must not be null");
        this.typeCode = Objects.requireNonNull(typeCode, "typeCode must not be null");
        this.typeVersion = typeVersion;
        this.specification = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(specification, "specification must not be null")));
        this.visibility = Objects.requireNonNull(visibility, "visibility must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.publishedAt = publishedAt;
        this.withdrawnAt = withdrawnAt;
        this.version = version;
    }

    public static Capability register(CapabilityId id, NetworkId networkId, CapabilityOwner owner,
                                      OrganizationId accountableOrganizationId, String name, String description,
                                      CapabilityType type, Map<String, Object> specification,
                                      CapabilityVisibility visibility, Instant now) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty() || normalizedName.length() > NAME_MAX_LENGTH) {
            throw new ValidationException("INVALID_CAPABILITY_NAME",
                    "The capability name must be between 1 and " + NAME_MAX_LENGTH + " characters");
        }
        if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new ValidationException("INVALID_CAPABILITY_DESCRIPTION",
                    "The description must be at most " + DESCRIPTION_MAX_LENGTH + " characters");
        }
        Map<String, Object> spec = specification == null ? Map.of() : specification;
        type.schema().validate(spec);
        Capability capability = new Capability(id, networkId, owner, accountableOrganizationId, normalizedName,
                description, type.id(), type.code(), type.version(), spec, visibility, CapabilityStatus.DRAFT, now,
                null, null, null);
        capability.registerEvent(new CapabilityRegistered(now, id, networkId, owner.type().name(), owner.id(),
                type.code()));
        return capability;
    }

    public static Capability restore(CapabilityId id, NetworkId networkId, CapabilityOwner owner,
                                     OrganizationId accountableOrganizationId, String name, String description,
                                     UUID typeId, String typeCode, int typeVersion, Map<String, Object> specification,
                                     CapabilityVisibility visibility, CapabilityStatus status, Instant createdAt,
                                     Instant publishedAt, Instant withdrawnAt, Long version) {
        return new Capability(id, networkId, owner, accountableOrganizationId, name, description, typeId, typeCode,
                typeVersion, specification, visibility, status, createdAt, publishedAt, withdrawnAt, version);
    }

    public void publish(CapabilityVisibility requested, Instant now) {
        requireNotWithdrawn();
        if (status != CapabilityStatus.DRAFT) {
            throw new ConflictException("CAPABILITY_ALREADY_PUBLISHED", "Capability " + id + " is already published");
        }
        if (requested != null) {
            visibility = requested;
        }
        status = CapabilityStatus.PUBLISHED;
        publishedAt = now;
        registerEvent(new CapabilityPublished(now, id, networkId, visibility.name()));
    }

    public void changeVisibility(CapabilityVisibility requested, Instant now) {
        requireNotWithdrawn();
        Objects.requireNonNull(requested, "visibility must not be null");
        if (requested == visibility) {
            return;
        }
        visibility = requested;
        registerEvent(new CapabilityVisibilityChanged(now, id, networkId, visibility.name()));
    }

    public void withdraw(Instant now) {
        requireNotWithdrawn();
        status = CapabilityStatus.WITHDRAWN;
        withdrawnAt = now;
        registerEvent(new CapabilityWithdrawn(now, id, networkId));
    }

    public static boolean manages(CapabilityOwner owner, OrganizationId accountableOrganizationId,
                                  IdentityId identityId, OrganizationId organizationId) {
        boolean self = owner.type().isIdentity() && identityId != null && owner.id().equals(identityId.value());
        boolean accountable = accountableOrganizationId != null && accountableOrganizationId.equals(organizationId);
        return self || accountable;
    }

    public boolean isManagedBy(IdentityId identityId, OrganizationId organizationId) {
        return manages(owner, accountableOrganizationId, identityId, organizationId);
    }

    public boolean isVisibleTo(IdentityId identityId, OrganizationId organizationId) {
        return isManagedBy(identityId, organizationId) || isDiscoverable();
    }

    public boolean isDiscoverable() {
        return status == CapabilityStatus.PUBLISHED && visibility != CapabilityVisibility.PRIVATE;
    }

    public boolean isAvailable() {
        return status == CapabilityStatus.PUBLISHED;
    }

    private void requireNotWithdrawn() {
        if (status == CapabilityStatus.WITHDRAWN) {
            throw new ConflictException("CAPABILITY_WITHDRAWN", "Capability " + id + " has been withdrawn");
        }
    }

    @Override
    public CapabilityId id() {
        return id;
    }

    public NetworkId networkId() {
        return networkId;
    }

    public CapabilityOwner owner() {
        return owner;
    }

    public Optional<OrganizationId> accountableOrganizationId() {
        return Optional.ofNullable(accountableOrganizationId);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public UUID typeId() {
        return typeId;
    }

    public String typeCode() {
        return typeCode;
    }

    public int typeVersion() {
        return typeVersion;
    }

    public Map<String, Object> specification() {
        return specification;
    }

    public CapabilityVisibility visibility() {
        return visibility;
    }

    public CapabilityStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> publishedAt() {
        return Optional.ofNullable(publishedAt);
    }

    public Optional<Instant> withdrawnAt() {
        return Optional.ofNullable(withdrawnAt);
    }

    public Long version() {
        return version;
    }
}
