package com.nexusphere.ledger.policy.api.rest;

import com.nexusphere.ledger.policy.application.PolicyService;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/policies")
class PolicyController {

    record PolicyRequest(String text) {
    }

    record PolicyResponse(String hash, String text, List<String> policies, String defaultEffect, Instant createdAt,
                          Instant activatedAt) {
    }

    private final PolicyService policies;

    PolicyController(PolicyService policies) {
        this.policies = policies;
    }

    @PutMapping
    PolicyResponse activate(Caller caller, @RequestBody PolicyRequest request) {
        caller.requireOperator();
        return response(policies.activate(request.text(), "operator"));
    }

    @GetMapping
    PolicyResponse active(Caller caller) {
        caller.requireOperator();
        return response(policies.active().orElseThrow(() -> LedgerException.notFound("An active policy set")));
    }

    @GetMapping("/{hash}")
    PolicyResponse get(Caller caller, @PathVariable String hash) {
        caller.requireOperator();
        PolicyService.PolicySetText set = policies.get(hash);
        return new PolicyResponse(set.hash(), set.text(), set.policies(), effect(), set.createdAt(), null);
    }

    private PolicyResponse response(PolicyService.Active active) {
        return new PolicyResponse(active.set().hash(), active.set().text(), active.set().policies(), effect(),
                active.set().createdAt(), active.activatedAt());
    }

    private String effect() {
        return policies.defaultEffect();
    }
}
