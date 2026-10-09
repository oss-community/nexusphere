package com.nexusphere.ledger.transparency.api.rest;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.evidence.application.CheckpointService;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.LedgerException;
import com.nexusphere.ledger.transparency.application.TransparencyLog;
import com.nexusphere.ledger.transparency.domain.repository.LogCheckpointRepository.StoredCheckpoint;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/evidence/{id}")
class StatementController {

    static final MediaType COSE = MediaType.parseMediaType("application/cose");

    private final EvidenceService evidence;
    private final CheckpointService checkpoints;
    private final TransparencyLog log;

    StatementController(EvidenceService evidence, CheckpointService checkpoints, TransparencyLog log) {
        this.evidence = evidence;
        this.checkpoints = checkpoints;
        this.log = log;
    }

    @GetMapping("/statement")
    ResponseEntity<byte[]> statement(Caller caller, @PathVariable UUID id) {
        return ResponseEntity.ok().contentType(COSE).body(log.statement(entry(caller, id)));
    }

    @GetMapping("/receipt")
    ResponseEntity<byte[]> receipt(Caller caller, @PathVariable UUID id,
                                   @RequestParam(required = false) Long treeSize) {
        EvidenceEntry entry = entry(caller, id);
        StoredCheckpoint checkpoint;
        if (treeSize != null) {
            checkpoint = log.find(treeSize).filter(c -> c.size() >= entry.sequence())
                    .orElseThrow(() -> LedgerException.invalid("The receipt request has invalid fields.",
                            Map.of("treeSize", "must be the size of a log checkpoint that includes the entry")));
        } else {
            checkpoint = log.latest().filter(c -> c.size() >= entry.sequence()).orElseGet(() -> {
                checkpoints.checkpointHead();
                return log.latest().filter(c -> c.size() >= entry.sequence()).orElseThrow();
            });
        }
        return ResponseEntity.ok().contentType(COSE).body(log.receipt(entry, checkpoint));
    }

    private EvidenceEntry entry(Caller caller, UUID id) {
        EvidenceEntry entry = evidence.get(id);
        if (!caller.canActAs(entry.agentId())) {
            throw LedgerException.notFound("Evidence " + id);
        }
        return entry;
    }
}
