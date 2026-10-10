package com.nexusphere.ledger.evidence.api.rest;

import com.nexusphere.ledger.evidence.application.LegalHoldService;
import com.nexusphere.ledger.server.security.Caller;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/legal-holds")
class LegalHoldController {

    record PlaceRequest(String principalId, String reason) {
    }

    record ReleaseRequest(String reason) {
    }

    private final LegalHoldService holds;

    LegalHoldController(LegalHoldService holds) {
        this.holds = holds;
    }

    @PostMapping
    ResponseEntity<LegalHoldService.LegalHold> place(Caller caller, @RequestBody PlaceRequest request) {
        caller.requireOperator();
        LegalHoldService.LegalHold hold = holds.place(request.principalId(), request.reason());
        return ResponseEntity.created(URI.create("/api/v1/legal-holds/" + hold.id())).body(hold);
    }

    @PostMapping("/{id}/release")
    LegalHoldService.LegalHold release(Caller caller, @PathVariable UUID id, @RequestBody ReleaseRequest request) {
        caller.requireOperator();
        return holds.release(id, request.reason());
    }

    @GetMapping("/{id}")
    LegalHoldService.LegalHold get(Caller caller, @PathVariable UUID id) {
        caller.requireOperator();
        return holds.get(id);
    }

    @GetMapping
    Map<String, List<LegalHoldService.LegalHold>> list(Caller caller,
                                                       @RequestParam(defaultValue = "false") boolean active) {
        caller.requireOperator();
        return Map.of("items", holds.list(active));
    }
}
