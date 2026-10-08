package com.nexusphere.integration.infrastructure.persistence;

import com.nexusphere.integration.domain.model.OutboxKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "integration", name = "ledger_outbox")
class OutboxEntity {

    @Id
    private UUID id;

    @Column(insertable = false, updatable = false)
    private Long seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxKind kind;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    protected OutboxEntity() {
    }

    OutboxEntity(UUID id, OutboxKind kind, String payload, Instant createdAt) {
        this.id = id;
        this.kind = kind;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    void sent(Instant at) {
        sentAt = at;
        attempts++;
        lastError = null;
    }

    void failed(String error) {
        attempts++;
        lastError = error;
    }

    void rejected(String error, Instant at) {
        failed(error);
        rejectedAt = at;
    }

    UUID getId() {
        return id;
    }

    OutboxKind getKind() {
        return kind;
    }

    String getPayload() {
        return payload;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    int getAttempts() {
        return attempts;
    }

    String getLastError() {
        return lastError;
    }
}
