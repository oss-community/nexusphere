package com.nexusphere.ledger.ask.api.rest;

import com.nexusphere.ledger.ask.application.AskService;
import com.nexusphere.ledger.ask.application.Citations;
import com.nexusphere.ledger.ask.application.QueryPlan;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.server.security.Caller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
class AskController {

    record AskRequest(String question) {
    }

    record Citation(long sequence, String id, Instant occurredAt, String agentId, String principalId, String action,
                    String target, String decision, String outcome, String hash, boolean erased,
                    AskService.Checks checks) {

        static Citation of(AskService.Checked checked) {
            EvidenceEntry e = checked.entry();
            return new Citation(e.sequence(), e.id().toString(), e.occurredAt(), e.agentId(), e.principalId(),
                    e.action(), e.target(), e.decision(), e.outcome(), e.hash(), e.erased(), checked.checks());
        }
    }

    record AskResponse(String question, String answer, List<Citations.Sentence> sentences, int droppedSentences,
                       List<Citation> citations, List<Long> unverified, QueryPlan plan, String model,
                       long considered, Long checkpointSize, long recordedAs) {

        static AskResponse of(AskService.Result r) {
            return new AskResponse(r.question(), r.answer(), r.sentences(), r.droppedSentences(),
                    r.citations().stream().map(Citation::of).toList(), r.unverified(), r.plan(), r.model(),
                    r.considered(), r.checkpointSize(), r.recordedAs());
        }
    }

    private final AskService ask;

    AskController(AskService ask) {
        this.ask = ask;
    }

    @PostMapping("/ask")
    AskResponse ask(Caller caller, @RequestBody AskRequest request) {
        caller.requireOperator();
        return AskResponse.of(ask.ask(request.question(), null));
    }

    @PostMapping("/principal/ask")
    AskResponse askAsPrincipal(Caller caller, @RequestBody AskRequest request) {
        String principalId = caller.requirePrincipal().principalId();
        return AskResponse.of(ask.ask(request.question(), principalId));
    }
}
