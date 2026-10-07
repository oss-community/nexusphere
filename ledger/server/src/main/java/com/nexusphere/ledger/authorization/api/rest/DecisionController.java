package com.nexusphere.ledger.authorization.api.rest;

import com.nexusphere.ledger.authorization.application.DecisionService;
import com.nexusphere.ledger.authorization.domain.model.DecisionRecord;
import com.nexusphere.ledger.authorization.domain.model.DecisionRequest;
import com.nexusphere.ledger.authorization.domain.model.DecisionResult;
import com.nexusphere.ledger.authorization.domain.model.OutcomeReport;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/decisions")
class DecisionController {

    record DecideRequest(String agentId, String principalId, String action, String target, String inputHash,
                         String correlationId, Map<String, String> attributes) {
    }

    record OutcomeRequest(Outcome outcome, String outputHash, String reason, Map<String, String> attributes) {
    }

    record EvidenceRef(UUID id, long sequence, String hash) {

        static EvidenceRef of(EvidenceEntry e) {
            return new EvidenceRef(e.id(), e.sequence(), e.hash());
        }
    }

    record DecisionResponse(UUID decisionId, String agentId, String principalId, String action, String target,
                            String decision, String reasonCode, UUID grantId, Instant decidedAt, String outcome,
                            UUID outcomeEvidenceId) {

        static DecisionResponse of(DecisionRecord d) {
            return new DecisionResponse(d.id(), d.agentId(), d.principalId(), d.action(), d.target(),
                    d.decision().name(), d.reasonCode().name(), d.grantId(), d.decidedAt(),
                    d.outcome() == null ? null : d.outcome().name(), d.outcomeEvidenceId());
        }
    }

    record DecisionResultResponse(UUID decisionId, String decision, String reasonCode, String reason, UUID grantId,
                                  String outcome, EvidenceRef evidence) {

        static DecisionResultResponse of(DecisionResult r) {
            DecisionRecord d = r.decision();
            return new DecisionResultResponse(d.id(), d.decision().name(), d.reasonCode().name(), r.reason(),
                    d.grantId(), r.evidence().outcome(), EvidenceRef.of(r.evidence()));
        }
    }

    private final DecisionService decisions;

    DecisionController(DecisionService decisions) {
        this.decisions = decisions;
    }

    @PostMapping
    ResponseEntity<DecisionResultResponse> decide(Caller caller, @RequestBody DecideRequest request) {
        String agentId = request.agentId();
        if (!caller.isOperator()) {
            if (agentId != null && !caller.canActAs(agentId)) {
                throw LedgerException.forbidden("An agent may only ask for its own decisions.");
            }
            agentId = caller.agentId();
        }
        DecisionResult result = decisions.decide(new DecisionRequest(agentId, request.principalId(),
                request.action(), request.target(), request.inputHash(), request.correlationId(),
                request.attributes()));
        return ResponseEntity.created(URI.create("/api/v1/decisions/" + result.decision().id()))
                .body(DecisionResultResponse.of(result));
    }

    @PostMapping("/{id}/outcome")
    DecisionResultResponse outcome(Caller caller, @PathVariable UUID id, @RequestBody OutcomeRequest request) {
        return DecisionResultResponse.of(decisions.reportOutcome(id, caller.agentId(),
                new OutcomeReport(request.outcome(), request.outputHash(), request.reason(), request.attributes())));
    }

    @GetMapping("/{id}")
    DecisionResponse get(Caller caller, @PathVariable UUID id) {
        return DecisionResponse.of(decisions.get(id, caller.agentId()));
    }
}
