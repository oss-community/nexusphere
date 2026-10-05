package com.nexusphere.delegation.api.rest;

import com.nexusphere.delegation.application.DelegationService;
import com.nexusphere.delegation.domain.model.Delegation;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.id.Identifier;
import com.nexusphere.shared.id.PrincipalId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/delegations")
class DelegationController {

    record ConstraintsBody(List<String> capabilityTypes, List<String> networks, List<String> resourceTypes) {
    }

    record GrantDelegationRequest(String delegatePrincipalId, List<String> actions, ConstraintsBody constraints,
                                  Instant validFrom, Instant validUntil) {
    }

    record DelegationResponse(String id, String networkId, String delegatorPrincipalId, String delegatePrincipalId,
                              Set<String> actions, ConstraintsBody constraints, String status, boolean effective,
                              Instant validFrom, Instant validUntil, Instant createdAt, Instant revokedAt,
                              Long version) {

        static DelegationResponse of(Delegation delegation, Instant now) {
            ConstraintsBody constraints = new ConstraintsBody(List.copyOf(delegation.constraints().capabilityTypes()),
                    delegation.constraints().networks().stream().map(Object::toString).sorted().toList(),
                    List.copyOf(delegation.constraints().resourceTypes()));
            return new DelegationResponse(delegation.id().toString(), delegation.networkId().toString(),
                    delegation.delegatorPrincipalId().toString(), delegation.delegatePrincipalId().toString(),
                    delegation.actions(), constraints, delegation.effectiveStatus(now).name(),
                    delegation.isEffective(now), delegation.validFrom(), delegation.validUntil().orElse(null),
                    delegation.createdAt(), delegation.revokedAt().orElse(null), delegation.version());
        }
    }

    private final DelegationService delegations;
    private final TimeProvider time;

    DelegationController(DelegationService delegations, TimeProvider time) {
        this.delegations = delegations;
        this.time = time;
    }

    @PostMapping
    ResponseEntity<DelegationResponse> grant(@PathVariable String networkId, @RequestBody GrantDelegationRequest request,
                                             PrincipalContext principal, ExecutionContext context) {
        ConstraintsBody constraints = request.constraints() == null ? new ConstraintsBody(null, null, null)
                : request.constraints();
        Delegation granted = delegations.grant(principal, new DelegationService.Grant(request.delegatePrincipalId(),
                request.actions(), constraints.capabilityTypes(), constraints.networks(), constraints.resourceTypes(),
                request.validFrom(), request.validUntil()), context);
        return ResponseEntity.created(URI.create("/api/v1/networks/" + networkId + "/delegations/" + granted.id()))
                .body(DelegationResponse.of(granted, time.now()));
    }

    @GetMapping
    List<DelegationResponse> list(PrincipalContext principal,
                                  @RequestParam(required = false) String delegatePrincipalId,
                                  @RequestParam(required = false) String delegatorPrincipalId,
                                  @RequestParam(defaultValue = "false") boolean effective) {
        Instant now = time.now();
        DelegationService.Filter filter = new DelegationService.Filter(
                delegatePrincipalId == null ? null : PrincipalId.of(delegatePrincipalId),
                delegatorPrincipalId == null ? null : PrincipalId.of(delegatorPrincipalId), effective);
        return delegations.list(principal, filter).stream().map(found -> DelegationResponse.of(found, now)).toList();
    }

    @GetMapping("/{delegationId}")
    DelegationResponse get(PrincipalContext principal, @PathVariable String delegationId) {
        return DelegationResponse.of(delegations.get(principal, Identifier.parse(delegationId, "DelegationId")),
                time.now());
    }

    @PostMapping("/{delegationId}/revoke")
    DelegationResponse revoke(PrincipalContext principal, @PathVariable String delegationId, ExecutionContext context) {
        return DelegationResponse.of(delegations.revoke(principal, Identifier.parse(delegationId, "DelegationId"),
                context), time.now());
    }

    @PostMapping("/{delegationId}/suspend")
    DelegationResponse suspend(PrincipalContext principal, @PathVariable String delegationId,
                               ExecutionContext context) {
        return DelegationResponse.of(delegations.suspend(principal, Identifier.parse(delegationId, "DelegationId"),
                context), time.now());
    }

    @PostMapping("/{delegationId}/resume")
    DelegationResponse resume(PrincipalContext principal, @PathVariable String delegationId, ExecutionContext context) {
        return DelegationResponse.of(delegations.resume(principal, Identifier.parse(delegationId, "DelegationId"),
                context), time.now());
    }
}
