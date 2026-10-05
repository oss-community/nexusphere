package com.nexusphere.shared;

import com.nexusphere.shared.context.CorrelationId;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.domain.AggregateRoot;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.event.EventEnvelope;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;
import com.nexusphere.shared.reference.ResourceReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedPrimitivesTest {

    record SomethingHappened(UUID eventId, Instant occurredAt, Optional<NetworkId> networkId) implements DomainEvent {
    }

    static final class Sample extends AggregateRoot<UUID> {
        private final UUID id = UUID.randomUUID();

        @Override
        public UUID id() {
            return id;
        }

        void act() {
            registerEvent(new SomethingHappened(UUID.randomUUID(), Instant.EPOCH, Optional.empty()));
        }
    }

    @Test
    void identifiersParseUuidsAndRejectGarbage() {
        UUID uuid = UUID.randomUUID();
        assertThat(NetworkId.of(uuid.toString()).value()).isEqualTo(uuid);
        assertThat(NetworkId.of(uuid.toString())).isEqualTo(new NetworkId(uuid));

        assertThatThrownBy(() -> NetworkId.of("not-a-uuid"))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).category()).isEqualTo(ErrorCategory.VALIDATION_ERROR));
        assertThatThrownBy(() -> PrincipalId.of(" ")).isInstanceOf(ValidationException.class);
    }

    @Test
    void correlationIdKeepsWellFormedClientValuesAndReplacesOthers() {
        assertThat(CorrelationId.fromNullable("req-42").value()).isEqualTo("req-42");
        assertThat(CorrelationId.fromNullable("bad value; drop table").value()).isNotEqualTo("bad value; drop table");
        assertThat(CorrelationId.fromNullable(null).value()).hasSize(36);
        assertThatThrownBy(() -> new CorrelationId("x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aggregateEventsArePulledExactlyOnce() {
        Sample sample = new Sample();
        sample.act();
        sample.act();

        assertThat(sample.pullEvents()).hasSize(2);
        assertThat(sample.pullEvents()).isEmpty();
    }

    @Test
    void envelopeTakesMetadataFromEventAndContext() {
        NetworkId network = NetworkId.newId();
        PrincipalId principal = PrincipalId.newId();
        CorrelationId correlation = CorrelationId.newId();
        ExecutionContext context = new ExecutionContext(correlation, null, principal, network, null);
        SomethingHappened event = new SomethingHappened(UUID.randomUUID(), Instant.parse("2026-10-05T00:00:00Z"), Optional.empty());

        EventEnvelope envelope = EventEnvelope.wrap(event, context, null);

        assertThat(envelope.eventType()).isEqualTo("shared.SomethingHappened");
        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.networkId()).isEqualTo(network);
        assertThat(envelope.principalId()).isEqualTo(principal);
        assertThat(envelope.correlationId()).isEqualTo(correlation);
        assertThat(envelope.timestamp()).isEqualTo(event.occurredAt());
    }

    @Test
    void resourceReferenceRequiresAllParts() {
        assertThatThrownBy(() -> new ResourceReference("AGREEMENT", " ", NetworkId.newId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ResourceReference("AGREEMENT", "a-1", NetworkId.newId()).resourceType()).isEqualTo("AGREEMENT");
    }
}
