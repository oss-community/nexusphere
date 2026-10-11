package com.nexusphere.ledger.approval.api.rest;

import com.nexusphere.ledger.approval.api.rest.ApprovalResponse.AnswerRequest;
import com.nexusphere.ledger.approval.api.rest.ApprovalResponse.ApprovalPage;
import com.nexusphere.ledger.approval.application.Approval;
import com.nexusphere.ledger.approval.application.ApprovalService;
import com.nexusphere.ledger.policy.application.CedarPolicies;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approvals")
class ApprovalController {

    private final ApprovalService approvals;

    ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    ApprovalPage list(Caller caller,
                      @RequestParam(required = false) String status,
                      @RequestParam(required = false) String agentId,
                      @RequestParam(required = false) String principalId,
                      @RequestParam(defaultValue = "100") int limit) {
        String agent = agentId;
        if (!caller.isOperator()) {
            if (!caller.isAgent() || agentId != null && !caller.canActAs(agentId)) {
                throw LedgerException.forbidden("An agent may only list its own approvals.");
            }
            agent = caller.agentId();
        }
        return new ApprovalPage(approvals.list(ApprovalResponse.status(status), principalId, agent, limit).stream()
                .map(ApprovalResponse::of).toList());
    }

    @GetMapping("/{id}")
    ApprovalResponse get(Caller caller, @PathVariable UUID id) {
        Approval approval = approvals.get(id);
        if (!caller.isOperator() && !(caller.isAgent() && caller.canActAs(approval.agentId()))) {
            throw LedgerException.notFound("Approval " + id);
        }
        return ApprovalResponse.of(approval);
    }

    @PostMapping("/{id}/approve")
    ApprovalResponse approve(Caller caller, @PathVariable UUID id,
                             @RequestBody(required = false) AnswerRequest request) {
        caller.requireOperator();
        return ApprovalResponse.of(approvals.approve(id, operator(), request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/deny")
    ApprovalResponse deny(Caller caller, @PathVariable UUID id, @RequestBody(required = false) AnswerRequest request) {
        caller.requireOperator();
        return ApprovalResponse.of(approvals.deny(id, operator(), request == null ? null : request.reason()));
    }

    private static ApprovalService.Resolver operator() {
        return new ApprovalService.Resolver(CedarPolicies.OPERATOR, CedarPolicies.OPERATOR, null, Map.of());
    }
}
