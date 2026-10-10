package com.nexusphere.ledger.evidence.api.rest;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.chain.TimestampToken;
import com.nexusphere.ledger.evidence.application.CheckpointService;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.transparency.application.TimestampService;
import com.nexusphere.ledger.transparency.application.WitnessCollector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/checkpoints")
class CheckpointController {

    record CheckpointResponse(String format, long sequence, String headHash, Instant createdAt, String keyId,
                              List<Checkpoint.Profile> profiles, String signature, String timestamp,
                              Instant timestampedAt) {

        static CheckpointResponse of(SignedCheckpoint signed) {
            Checkpoint c = signed.checkpoint();
            return new CheckpointResponse(c.format(), c.sequence(), c.headHash(), c.createdAt(), c.keyId(),
                    c.profiles(), signed.signature(), signed.timestamp(),
                    signed.timestamp() == null ? null : TimestampToken.parse(signed.timestamp()).genTime());
        }
    }

    record CheckpointPage(List<CheckpointResponse> items, Long nextAfter) {
    }

    private final CheckpointService checkpoints;
    private final WitnessCollector witnesses;
    private final TimestampService timestamps;

    CheckpointController(CheckpointService checkpoints, WitnessCollector witnesses, TimestampService timestamps) {
        this.checkpoints = checkpoints;
        this.witnesses = witnesses;
        this.timestamps = timestamps;
    }

    @PostMapping
    CheckpointResponse create(Caller caller) {
        caller.requireOperator();
        CheckpointResponse created = CheckpointResponse.of(timestamps.stamp(checkpoints.create()));
        witnesses.collect();
        return created;
    }

    @GetMapping("/latest")
    CheckpointResponse latest() {
        return CheckpointResponse.of(checkpoints.latest());
    }

    @GetMapping
    CheckpointPage list(@RequestParam(defaultValue = "0") long after,
                        @RequestParam(defaultValue = "100") int limit) {
        List<SignedCheckpoint> page = checkpoints.list(after, limit);
        Long nextAfter = page.size() == limit ? page.getLast().checkpoint().sequence() : null;
        return new CheckpointPage(page.stream().map(CheckpointResponse::of).toList(), nextAfter);
    }
}
