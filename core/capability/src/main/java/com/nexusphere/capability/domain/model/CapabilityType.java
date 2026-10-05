package com.nexusphere.capability.domain.model;

import com.nexusphere.shared.error.ValidationException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CapabilityType {

    public static final int CODE_MAX_LENGTH = 100;
    public static final int NAME_MAX_LENGTH = 200;
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*");

    private final UUID id;
    private final String code;
    private final String name;
    private final String description;
    private final int version;
    private final CapabilitySchema schema;
    private final Instant createdAt;

    private CapabilityType(UUID id, String code, String name, String description, int version,
                           CapabilitySchema schema, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
        this.version = version;
        this.schema = Objects.requireNonNull(schema, "schema must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static CapabilityType register(UUID id, String code, String name, String description, int version,
                                          CapabilitySchema schema, Instant now) {
        String normalizedCode = code == null ? "" : code.trim();
        if (normalizedCode.length() > CODE_MAX_LENGTH || !CODE.matcher(normalizedCode).matches()) {
            throw new ValidationException("INVALID_CAPABILITY_TYPE_CODE",
                    "The capability type code must be lowercase dot-separated segments such as manufacturing.cnc");
        }
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty() || normalizedName.length() > NAME_MAX_LENGTH) {
            throw new ValidationException("INVALID_CAPABILITY_TYPE_NAME",
                    "The capability type name must be between 1 and " + NAME_MAX_LENGTH + " characters");
        }
        if (version < 1) {
            throw new ValidationException("INVALID_CAPABILITY_TYPE_VERSION", "The version must be at least 1");
        }
        return new CapabilityType(id, normalizedCode, normalizedName, description, version, schema, now);
    }

    public static CapabilityType restore(UUID id, String code, String name, String description, int version,
                                         CapabilitySchema schema, Instant createdAt) {
        return new CapabilityType(id, code, name, description, version, schema, createdAt);
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public int version() {
        return version;
    }

    public CapabilitySchema schema() {
        return schema;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
