package com.nexusphere.ledger.anomaly.application;

import com.nexusphere.ledger.server.cluster.Leadership;
import com.nexusphere.ledger.server.cluster.LeadershipChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class AnomalyScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnomalyScheduler.class);

    private final AnomalyService anomalies;
    private final Leadership leadership;

    AnomalyScheduler(AnomalyService anomalies, Leadership leadership) {
        this.anomalies = anomalies;
        this.leadership = leadership;
    }

    @EventListener
    void leadership(LeadershipChanged change) {
        try {
            if (change.leader()) {
                anomalies.refreshMetrics();
            } else {
                anomalies.clearMetrics();
            }
        } catch (RuntimeException e) {
            log.warn("Open anomalies cannot be read: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${ledger.anomaly.interval}", initialDelayString = "${ledger.anomaly.interval}")
    void detect() {
        if (!anomalies.enabled() || !leadership.isLeader()) {
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
