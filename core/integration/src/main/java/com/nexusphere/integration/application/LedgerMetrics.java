package com.nexusphere.integration.application;

import com.nexusphere.integration.domain.repository.LedgerOutbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
class LedgerMetrics implements MeterBinder {

    private final LedgerProperties properties;
    private final LedgerOutbox outbox;
    private MeterRegistry registry;

    LedgerMetrics(LedgerProperties properties, LedgerOutbox outbox) {
        this.properties = properties;
        this.outbox = outbox;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("nexusphere.ledger.outbox.pending", this,
                        m -> m.properties.enabled() ? m.outbox.countPending() : 0)
                .description("Outbox messages not yet delivered to the ledger").register(registry);
    }

    void delivered(String result, int count) {
        if (registry != null && count > 0) {
            Counter.builder("nexusphere.ledger.outbox.delivered")
                    .description("Outbox messages handled by the forwarder, by result")
                    .tag("result", result)
                    .register(registry)
                    .increment(count);
        }
    }
}
