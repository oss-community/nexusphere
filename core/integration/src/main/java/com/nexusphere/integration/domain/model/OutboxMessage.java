package com.nexusphere.integration.domain.model;

import java.time.Instant;
import java.util.UUID;

public record OutboxMessage(UUID id, OutboxKind kind, String payload, Instant createdAt, int attempts,
                            String lastError) {

    public static OutboxMessage pending(OutboxKind kind, String payload, Instant now) {
        return new OutboxMessage(UUID.randomUUID(), kind, payload, now, 0, null);
    }
}
