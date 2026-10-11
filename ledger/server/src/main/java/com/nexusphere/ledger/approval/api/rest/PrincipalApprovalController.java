package com.nexusphere.ledger.approval.api.rest;

import com.nexusphere.ledger.approval.api.rest.ApprovalResponse.AnswerRequest;
import com.nexusphere.ledger.approval.api.rest.ApprovalResponse.ApprovalPage;
import com.nexusphere.ledger.approval.application.Approval;
import com.nexusphere.ledger.approval.application.ApprovalService;
import com.nexusphere.ledger.approval.application.Consents;
import com.nexusphere.ledger.policy.application.CedarPolicies;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.security.PrincipalIdentity;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/principal/approvals")
class PrincipalApprovalController {

    private final ApprovalService approvals;

    PrincipalApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    ApprovalPage list(Caller caller, @RequestParam(required = false) String status,
                      @RequestParam(defaultValue = "100") int limit) {
        PrincipalIdentity p = caller.requirePrincipal();
        return new ApprovalPage(approvals.list(ApprovalResponse.status(status), p.principalId(), null, limit)
                .stream().map(ApprovalResponse::of).toList());
    }

    @GetMapping("/{id}")
    ApprovalResponse get(Caller caller, @PathVariable UUID id) {
        PrincipalIdentity p = caller.requirePrincipal();
        Approval approval = approvals.get(id);
        if (!approval.principalId().equals(p.principalId())) {
            throw LedgerException.notFound("Approval " + id);
        }
        return ApprovalResponse.of(approval);
    }

    @PostMapping("/{id}/approve")
    ApprovalResponse approve(Caller caller, @PathVariable UUID id,
                             @RequestBody(required = false) AnswerRequest request) {
        return ApprovalResponse.of(approvals.approve(id, resolver(caller.requirePrincipal()),
                request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/deny")
    ApprovalResponse deny(Caller caller, @PathVariable UUID id, @RequestBody(required = false) AnswerRequest request) {
        return ApprovalResponse.of(approvals.deny(id, resolver(caller.requirePrincipal()),
                request == null ? null : request.reason()));
    }

    private static ApprovalService.Resolver resolver(PrincipalIdentity p) {
        return new ApprovalService.Resolver(CedarPolicies.PRINCIPAL, p.subject(), p.principalId(), Consents.of(p));
    }
}
