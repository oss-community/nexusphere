package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.transparency.application.TimestampService;
import com.nexusphere.ledger.transparency.application.WitnessCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class CheckpointScheduler {

    private static final Logger log = LoggerFactory.getLogger(CheckpointScheduler.class);

    private final CheckpointService checkpoints;
    private final WitnessCollector witnesses;
    private final TimestampService timestamps;

    CheckpointScheduler(CheckpointService checkpoints, WitnessCollector witnesses, TimestampService timestamps) {
        this.checkpoints = checkpoints;
        this.witnesses = witnesses;
        this.timestamps = timestamps;
    }

    @Scheduled(fixedDelayString = "${ledger.checkpoint.interval}", initialDelayString = "${ledger.checkpoint.interval}")
    void checkpoint() {
        try {
            checkpoints.checkpointHead().ifPresent(signed ->
                    log.debug("Checkpoint at sequence {}", signed.checkpoint().sequence()));
        } catch (RuntimeException e) {
            log.warn("Scheduled checkpoint failed", e);
        }
        timestamps.stampMissing();
        witnesses.collect();
    }
}
