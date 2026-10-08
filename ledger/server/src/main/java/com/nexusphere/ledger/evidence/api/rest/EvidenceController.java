package com.nexusphere.ledger.evidence.api.rest;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.Decision;
import com.nexusphere.ledger.evidence.domain.model.EvidenceQuery;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.LedgerHead;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class EvidenceController {

    record EvidenceRequest(
            Instant occurredAt,
            String agentId,
            String principalId,
            String action,
            String target,
            Decision decision,
            String reason,
            String delegationId,
            String inputHash,
            String outputHash,
            Outcome outcome,
            String correlationId,
            Map<String, String> attributes) {

        EvidenceSubmission toSubmission() {
            return new EvidenceSubmission(occurredAt, agentId, principalId, action, target, decision, reason,
                    delegationId, inputHash, outputHash, outcome, correlationId, attributes);
        }
    }

    record EvidenceResponse(
            String format,
            UUID id,
            long sequence,
            Instant occurredAt,
            Instant recordedAt,
            String agentId,
            String principalId,
            String action,
            String target,
            String decision,
            String reason,
            String delegationId,
            String inputHash,
            String outputHash,
            String outcome,
            String correlationId,
            Map<String, String> attributes,
            String previousHash,
            String hash) {

        static EvidenceResponse of(EvidenceEntry e) {
            return new EvidenceResponse(EvidenceEntry.FORMAT, e.id(), e.sequence(), e.occurredAt(), e.recordedAt(),
                    e.agentId(), e.principalId(), e.action(), e.target(), e.decision(), e.reason(), e.delegationId(),
                    e.inputHash(), e.outputHash(), e.outcome(), e.correlationId(), e.attributes(), e.previousHash(),
                    e.hash());
        }
    }

    record EvidencePage(List<EvidenceResponse> items, Long nextAfter) {
    }

    record EvidenceBatch(List<EvidenceRequest> items) {
    }

    record EvidenceBatchResponse(List<EvidenceResponse> items) {
    }

    record HeadResponse(long sequence, String hash) {
    }

    private final EvidenceService evidence;

    EvidenceController(EvidenceService evidence) {
        this.evidence = evidence;
    }

    @PostMapping("/evidence")
    ResponseEntity<EvidenceResponse> record(Caller caller, @RequestBody EvidenceRequest request) {
        if (request.agentId() != null && !caller.canActAs(request.agentId())) {
            throw LedgerException.forbidden("An agent may only record evidence for itself.");
        }
        EvidenceEntry entry = evidence.record(request.toSubmission());
        return ResponseEntity.created(URI.create("/api/v1/evidence/" + entry.id())).body(EvidenceResponse.of(entry));
    }

    @PostMapping("/evidence/batch")
    ResponseEntity<EvidenceBatchResponse> recordAll(Caller caller, @RequestBody EvidenceBatch batch) {
        List<EvidenceRequest> items = batch == null || batch.items() == null ? List.of() : batch.items();
        for (EvidenceRequest request : items) {
            if (request == null) {
                throw LedgerException.invalid("The batch has invalid fields.", Map.of("items", "must not be null"));
            }
            if (request.agentId() != null && !caller.canActAs(request.agentId())) {
                throw LedgerException.forbidden("An agent may only record evidence for itself.");
            }
        }
        List<EvidenceEntry> entries = evidence.recordAll(items.stream().map(EvidenceRequest::toSubmission).toList());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new EvidenceBatchResponse(entries.stream().map(EvidenceResponse::of).toList()));
    }

    @GetMapping("/evidence/{id}")
    EvidenceResponse get(Caller caller, @PathVariable UUID id) {
        EvidenceEntry entry = evidence.get(id);
        if (!caller.canActAs(entry.agentId())) {
            throw LedgerException.notFound("Evidence " + id);
        }
        return EvidenceResponse.of(entry);
    }

    @GetMapping("/evidence")
    EvidencePage find(Caller caller,
                      @RequestParam(required = false) String agentId,
                      @RequestParam(required = false) String principalId,
                      @RequestParam(defaultValue = "0") long after,
                      @RequestParam(defaultValue = "100") int limit) {
        if (!caller.isOperator()) {
            if (agentId != null && !caller.canActAs(agentId)) {
                throw LedgerException.forbidden("An agent may only read its own evidence.");
            }
            agentId = caller.agentId();
        }
        List<EvidenceEntry> entries = evidence.find(new EvidenceQuery(agentId, principalId, after, limit));
        Long nextAfter = entries.size() == limit ? entries.getLast().sequence() : null;
        return new EvidencePage(entries.stream().map(EvidenceResponse::of).toList(), nextAfter);
    }

    @GetMapping("/principal/evidence")
    EvidencePage forPrincipal(Caller caller,
                              @RequestParam(defaultValue = "0") long after,
                              @RequestParam(defaultValue = "100") int limit) {
        String principalId = caller.requirePrincipal().principalId();
        List<EvidenceEntry> entries = evidence.find(new EvidenceQuery(null, principalId, after, limit));
        Long nextAfter = entries.size() == limit ? entries.getLast().sequence() : null;
        return new EvidencePage(entries.stream().map(EvidenceResponse::of).toList(), nextAfter);
    }

    @GetMapping("/ledger/head")
    HeadResponse head() {
        LedgerHead head = evidence.head();
        return new HeadResponse(head.sequence(), head.hash());
    }
}
