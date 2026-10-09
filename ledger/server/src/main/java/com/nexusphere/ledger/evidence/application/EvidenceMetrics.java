package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.evidence.domain.repository.CheckpointRepository;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

@Component
class EvidenceMetrics implements MeterBinder {

    private final EvidenceRepository evidence;
    private final CheckpointRepository checkpoints;
    private final Clock clock;
    private MeterRegistry registry;

    EvidenceMetrics(EvidenceRepository evidence, CheckpointRepository checkpoints, Clock clock) {
        this.evidence = evidence;
        this.checkpoints = checkpoints;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("ledger.head.sequence", this, m -> m.evidence.head().sequence())
                .description("Sequence of the last evidence entry").register(registry);
        Gauge.builder("ledger.checkpoint.sequence", this, m -> m.latest().map(c -> c.checkpoint().sequence())
                        .orElse(0L))
                .description("Sequence covered by the latest signed checkpoint").register(registry);
        Gauge.builder("ledger.checkpoint.lag", this, m -> m.evidence.head().sequence()
                        - m.latest().map(c -> c.checkpoint().sequence()).orElse(0L))
                .description("Evidence entries not yet covered by a signed checkpoint").register(registry);
        Gauge.builder("ledger.checkpoint.age", this, m -> m.latest()
                        .map(c -> (double) Duration.between(c.checkpoint().createdAt(), m.clock.instant()).toSeconds())
                        .orElse(0.0))
                .baseUnit("seconds")
                .description("Seconds since the latest signed checkpoint").register(registry);
    }

    void recorded(EvidenceEntry entry) {
        if (registry == null) {
            return;
        }
        Counter.builder("ledger.evidence.recorded")
                .description("Evidence entries appended to the chain")
                .tag("decision", entry.decision() == null ? "none" : entry.decision())
                .tag("outcome", entry.outcome())
                .register(registry)
                .increment();
    }

    private Optional<SignedCheckpoint> latest() {
        return checkpoints.latest();
    }
}
