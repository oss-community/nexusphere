package com.nexusphere.organization.domain.model;

import com.nexusphere.organization.contract.OrganizationDeactivated;
import com.nexusphere.organization.contract.OrganizationRegistered;
import com.nexusphere.organization.contract.OrganizationRenamed;
import com.nexusphere.shared.domain.AggregateRoot;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

import java.time.Instant;
import java.util.Objects;

public final class Organization extends AggregateRoot<OrganizationId> {

    public static final int NAME_MAX_LENGTH = 120;

    private final OrganizationId id;
    private final NetworkId networkId;
    private String name;
    private OrganizationStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    private Organization(OrganizationId id, NetworkId networkId, String name, OrganizationStatus status,
                         Instant createdAt, Instant updatedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.networkId = Objects.requireNonNull(networkId, "networkId must not be null");
        this.name = name;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    public static Organization register(OrganizationId id, NetworkId networkId, String name, Instant now) {
        Organization organization = new Organization(id, networkId, normalizeName(name),
                OrganizationStatus.ACTIVE, now, now, null);
        organization.registerEvent(new OrganizationRegistered(now, networkId, id, organization.name));
        return organization;
    }

    public static Organization restore(OrganizationId id, NetworkId networkId, String name, OrganizationStatus status,
                                       Instant createdAt, Instant updatedAt, Long version) {
        return new Organization(id, networkId, name, status, createdAt, updatedAt, version);
    }

    public static String normalizeName(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty() || value.length() > NAME_MAX_LENGTH) {
            throw new ValidationException("INVALID_ORGANIZATION_NAME",
                    "Organization name must have 1 to " + NAME_MAX_LENGTH + " characters");
        }
        return value;
    }

    public void rename(String newName, Instant now) {
        requireActive();
        this.name = normalizeName(newName);
        this.updatedAt = now;
        registerEvent(new OrganizationRenamed(now, networkId, id, name));
    }

    public void deactivate(Instant now) {
        requireActive();
        this.status = OrganizationStatus.DEACTIVATED;
        this.updatedAt = now;
        registerEvent(new OrganizationDeactivated(now, networkId, id));
    }

    public boolean isActive() {
        return status == OrganizationStatus.ACTIVE;
    }

    private void requireActive() {
        if (!isActive()) {
            throw new ConflictException("ORGANIZATION_NOT_ACTIVE", "Organization " + id + " is not active");
        }
    }

    @Override
    public OrganizationId id() {
        return id;
    }

    public NetworkId networkId() {
        return networkId;
    }

    public String name() {
        return name;
    }

    public OrganizationStatus status() {
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
