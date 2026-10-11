package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.server.cluster.Leadership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final RetentionService retention;
    private final Leadership leadership;

    RetentionScheduler(RetentionService retention, Leadership leadership) {
        this.retention = retention;
        this.leadership = leadership;
    }

    @Scheduled(fixedDelayString = "${ledger.compliance.retention-interval}",
            initialDelayString = "${ledger.compliance.retention-interval}")
    void sweep() {
        if (!leadership.isLeader()) {
            return;
        }
        try {
            RetentionService.Sweep sweep = retention.sweep();
            if (sweep.expiredEntries() > 0 || !sweep.erasures().isEmpty()) {
                log.info("Retention expired {} entries and continued {} erasures", sweep.expiredEntries(),
                        sweep.erasures().size());
            }
        } catch (RuntimeException e) {
            log.warn("Scheduled retention sweep failed", e);
        }
    }
}
