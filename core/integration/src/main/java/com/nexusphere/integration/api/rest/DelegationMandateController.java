package com.nexusphere.integration.api.rest;

import com.nexusphere.integration.application.LedgerMandates;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.id.Identifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/delegations/{delegationId}/mandates")
class DelegationMandateController {

    record MandateRequest(String audience, Instant expiresAt) {
    }

    private final LedgerMandates mandates;

    DelegationMandateController(LedgerMandates mandates) {
        this.mandates = mandates;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> issue(@PathVariable String networkId, @PathVariable String delegationId,
                              @RequestBody(required = false) MandateRequest request, PrincipalContext principal) {
        return mandates.issue(principal, Identifier.parse(delegationId, "DelegationId"),
                request == null ? null : request.audience(), request == null ? null : request.expiresAt());
    }
}
