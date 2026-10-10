package com.nexusphere.ledger.evidence.api.rest;

import com.nexusphere.ledger.evidence.application.RetentionService;
import com.nexusphere.ledger.server.security.Caller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RetentionController {

    private final RetentionService retention;

    RetentionController(RetentionService retention) {
        this.retention = retention;
    }

    @PostMapping("/api/v1/retention/sweep")
    RetentionService.Sweep sweep(Caller caller) {
        caller.requireOperator();
        return retention.sweep();
    }
}
