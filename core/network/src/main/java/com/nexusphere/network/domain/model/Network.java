package com.nexusphere.network.domain.model;

import com.nexusphere.network.contract.NetworkActivated;
import com.nexusphere.network.contract.NetworkArchived;
import com.nexusphere.network.contract.NetworkCreated;
import com.nexusphere.network.contract.NetworkSuspended;
import com.nexusphere.shared.domain.AggregateRoot;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Objects;

public final class Network extends AggregateRoot<NetworkId> {

    public static final int NAME_MAX_LENGTH = 120;
    public static final int DESCRIPTION_MAX_LENGTH = 500;

    private final NetworkId id;
    private final String name;
    private final String description;
    private NetworkStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    private Network(NetworkId id, String name, String description, NetworkStatus status,
                    Instant createdAt, Instant updatedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = name;
        this.description = description;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    public static Network create(NetworkId id, String name, String description, Instant now) {
        Network network = new Network(id, normalizeName(name), normalizeDescription(description),
                NetworkStatus.PENDING, now, now, null);
        network.registerEvent(new NetworkCreated(now, id, network.name));
        return network;
    }

    public static Network restore(NetworkId id, String name, String description, NetworkStatus status,
                                  Instant createdAt, Instant updatedAt, Long version) {
        return new Network(id, name, description, status, createdAt, updatedAt, version);
    }

    public void activate(Instant now) {
        if (status == NetworkStatus.ACTIVE) {
            throw new ConflictException("NETWORK_ALREADY_ACTIVE", "Network " + id + " is already active");
        }
        requireStatus("activate", NetworkStatus.PENDING, NetworkStatus.SUSPENDED);
        changeStatus(NetworkStatus.ACTIVE, now);
        registerEvent(new NetworkActivated(now, id));
    }

    public void suspend(Instant now) {
        requireStatus("suspend", NetworkStatus.ACTIVE);
        changeStatus(NetworkStatus.SUSPENDED, now);
        registerEvent(new NetworkSuspended(now, id));
    }

    public void archive(Instant now) {
        requireStatus("archive", NetworkStatus.PENDING, NetworkStatus.ACTIVE, NetworkStatus.SUSPENDED);
        changeStatus(NetworkStatus.ARCHIVED, now);
        registerEvent(new NetworkArchived(now, id));
    }

    public boolean isActive() {
        return status == NetworkStatus.ACTIVE;
    }

    private void requireStatus(String action, NetworkStatus... allowed) {
        for (NetworkStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new ConflictException("INVALID_NETWORK_TRANSITION",
                "Cannot " + action + " network " + id + " in status " + status);
    }

    private void changeStatus(NetworkStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    private static String normalizeName(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty() || value.length() > NAME_MAX_LENGTH) {
            throw new ValidationException("INVALID_NETWORK_NAME",
                    "Network name must have 1 to " + NAME_MAX_LENGTH + " characters");
        }
        return value;
    }

    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String value = description.strip();
        if (value.length() > DESCRIPTION_MAX_LENGTH) {
            throw new ValidationException("INVALID_NETWORK_DESCRIPTION",
                    "Network description must have at most " + DESCRIPTION_MAX_LENGTH + " characters");
        }
        return value;
    }

    @Override
    public NetworkId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public NetworkStatus status() {
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
