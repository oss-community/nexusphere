package com.nexusphere.ledger.evidence.api.rest;

import com.nexusphere.ledger.evidence.application.PromptService;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/prompts")
class PromptController {

    record PromptRequest(String text) {
    }

    record PromptResponse(String hash) {
    }

    private final PromptService prompts;

    PromptController(PromptService prompts) {
        this.prompts = prompts;
    }

    @PutMapping
    ResponseEntity<PromptResponse> store(Caller caller, @RequestBody PromptRequest request) {
        String createdBy;
        if (caller.isOperator()) {
            createdBy = "operator";
        } else if (caller.isAgent()) {
            createdBy = "agent:" + caller.agentId();
        } else {
            throw LedgerException.forbidden("Only the ledger operator or an agent may store a prompt.");
        }
        PromptService.Stored stored = prompts.store(request.text(), createdBy);
        return ResponseEntity.status(stored.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new PromptResponse(stored.hash()));
    }

    @GetMapping("/{hash}")
    PromptService.Prompt get(Caller caller, @PathVariable String hash) {
        caller.requireOperator();
        return prompts.get(hash);
    }
}
