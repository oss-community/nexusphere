package com.nexusphere.shared.event;

import com.nexusphere.shared.context.CorrelationId;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Common event metadata (redesign §52). Audit consumes envelopes without knowing the payload types. */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant timestamp,
        NetworkId networkId,
        PrincipalId principalId,
        CorrelationId correlationId,
        UUID causationId,
        DomainEvent payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    public static EventEnvelope wrap(DomainEvent event, ExecutionContext context, UUID causationId) {
        return new EventEnvelope(
                event.eventId(),
                event.eventType(),
                event.eventVersion(),
                event.occurredAt(),
                event.networkId().orElse(context.networkId()),
                context.principalId(),
                context.correlationId(),
                causationId,
                event);
    }
}
