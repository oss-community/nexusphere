package com.nexusphere.ledger.evidence.api.rest;

import com.nexusphere.ledger.evidence.application.ErasureService;
import com.nexusphere.ledger.server.security.Caller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ErasureController {

    record ErasureRequest(String reason) {
    }

    private final ErasureService erasures;

    ErasureController(ErasureService erasures) {
        this.erasures = erasures;
    }

    @PostMapping("/api/v1/principals/{principalId}/erasure")
    ErasureService.Erasure erase(Caller caller, @PathVariable String principalId,
                                 @RequestBody(required = false) ErasureRequest request) {
        caller.requireOperator();
        return erasures.erase(principalId, request == null ? null : request.reason());
    }
}
