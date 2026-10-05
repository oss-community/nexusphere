package com.nexusphere.capability.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "capability", name = "capability_type")
class CapabilityTypeEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "type_version", nullable = false)
    private int typeVersion;

    @Column(nullable = false, columnDefinition = "text")
    private String schema;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CapabilityTypeEntity() {
    }

    CapabilityTypeEntity(UUID id, String code, String name, String description, int typeVersion, String schema,
                         Instant createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.typeVersion = typeVersion;
        this.schema = schema;
        this.createdAt = createdAt;
    }

    UUID getId() {
        return id;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    int getTypeVersion() {
        return typeVersion;
    }

    String getSchema() {
        return schema;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
