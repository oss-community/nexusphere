package com.nexusphere.ledger.evidence.api.rest;

import com.nexusphere.ledger.evidence.application.VerificationReport;
import com.nexusphere.ledger.evidence.application.VerificationService;
import com.nexusphere.ledger.server.security.Caller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class VerificationController {

    private final VerificationService verification;

    VerificationController(VerificationService verification) {
        this.verification = verification;
    }

    @GetMapping("/api/v1/verification")
    VerificationReport verify(Caller caller) {
        caller.requireOperator();
        return verification.verify();
    }
}
