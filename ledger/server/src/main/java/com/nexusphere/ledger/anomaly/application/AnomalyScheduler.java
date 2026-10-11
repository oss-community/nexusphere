package com.nexusphere.ledger.anomaly.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class AnomalyScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnomalyScheduler.class);

    private final AnomalyService anomalies;

    AnomalyScheduler(AnomalyService anomalies) {
        this.anomalies = anomalies;
    }

    @EventListener(ApplicationReadyEvent.class)
    void restore() {
        try {
            anomalies.refreshMetrics();
        } catch (RuntimeException e) {
            log.warn("Open anomalies cannot be read: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${ledger.anomaly.interval}", initialDelayString = "${ledger.anomaly.interval}")
    void detect() {
        if (!anomalies.enabled()) {
            return;
        }
        try {
            anomalies.detect().forEach(a -> log.warn("Anomaly {} of agent {} with score {}", a.kind(), a.agentId(),
                    a.score()));
        } catch (RuntimeException e) {
            log.warn("Anomaly detection failed: {}", e.getMessage());
        }
    }
}
