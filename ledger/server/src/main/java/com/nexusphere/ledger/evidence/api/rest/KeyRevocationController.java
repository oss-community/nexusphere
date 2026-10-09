package com.nexusphere.ledger.evidence.api.rest;

import com.nexusphere.ledger.evidence.application.KeyRevocationService;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.signing.KeyController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class KeyRevocationController {

    public record RevocationRequest(Instant compromisedAt, String reason) {
    }

    private final KeyRevocationService revocations;

    KeyRevocationController(KeyRevocationService revocations) {
        this.revocations = revocations;
    }

    @PostMapping("/api/v1/keys/{keyId}/revocation")
    KeyController.RevocationResponse revoke(Caller caller, @PathVariable String keyId,
                                            @RequestBody RevocationRequest request) {
        caller.requireOperator();
        return KeyController.RevocationResponse.of(revocations.revoke(keyId, request.compromisedAt(),
                request.reason()));
    }
}
