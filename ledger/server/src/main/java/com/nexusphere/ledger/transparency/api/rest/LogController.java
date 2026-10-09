package com.nexusphere.ledger.transparency.api.rest;

import com.nexusphere.ledger.server.web.LedgerException;
import com.nexusphere.ledger.transparency.application.TransparencyLog;
import com.nexusphere.ledger.transparency.application.WitnessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/public/v1")
class LogController {

    private static final MediaType TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final MediaType TREE_SIZE = MediaType.parseMediaType("text/x.tlog.size");

    private final TransparencyLog log;
    private final WitnessService witness;

    LogController(TransparencyLog log, WitnessService witness) {
        this.log = log;
        this.witness = witness;
    }

    @GetMapping("/log/checkpoint")
    @Transactional(readOnly = true)
    ResponseEntity<String> checkpoint() {
        return log.latest().map(checkpoint -> ResponseEntity.ok().contentType(TEXT).body(log.cosignedNote(checkpoint)))
                .orElseThrow(() -> LedgerException.notFound("A log checkpoint"));
    }

    @GetMapping("/log/key")
    ResponseEntity<String> key() {
        return ResponseEntity.ok().contentType(TEXT).body(log.noteKey().vkey() + "\n");
    }

    @GetMapping("/witness/key")
    ResponseEntity<String> witnessKey() {
        return ResponseEntity.ok().contentType(TEXT).body(log.witnessKey().vkey() + "\n");
    }

    @PostMapping("/witness/add-checkpoint")
    ResponseEntity<String> addCheckpoint(@RequestBody(required = false) String request) {
        if (!witness.enabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(TEXT).body("this ledger witnesses no log\n");
        }
        return switch (witness.addCheckpoint(request == null ? "" : request)) {
            case WitnessService.Cosigned cosigned -> ResponseEntity.ok().contentType(TEXT).body(cosigned.line());
            case WitnessService.Conflict conflict -> ResponseEntity.status(HttpStatus.CONFLICT).contentType(TREE_SIZE)
                    .body(conflict.size() + "\n");
            case WitnessService.Rejected rejected -> ResponseEntity.status(rejected.status()).contentType(TEXT)
                    .body(rejected.message() + "\n");
        };
    }
}
