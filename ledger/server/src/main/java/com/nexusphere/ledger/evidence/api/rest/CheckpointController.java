package com.nexusphere.ledger.evidence.api.rest;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.evidence.application.CheckpointService;
import com.nexusphere.ledger.server.security.Caller;
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
                              String signature) {

        static CheckpointResponse of(SignedCheckpoint signed) {
            Checkpoint c = signed.checkpoint();
            return new CheckpointResponse(Checkpoint.FORMAT, c.sequence(), c.headHash(), c.createdAt(), c.keyId(),
                    signed.signature());
        }
    }

    record CheckpointPage(List<CheckpointResponse> items, Long nextAfter) {
    }

    private final CheckpointService checkpoints;

    CheckpointController(CheckpointService checkpoints) {
        this.checkpoints = checkpoints;
    }

    @PostMapping
    CheckpointResponse create(Caller caller) {
        caller.requireOperator();
        return CheckpointResponse.of(checkpoints.create());
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
