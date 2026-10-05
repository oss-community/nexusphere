package com.nexusphere.authorization.api.rest;

import com.nexusphere.authorization.application.AuthorizationService;
import com.nexusphere.authorization.contract.AuthorizationDecision;
import com.nexusphere.authorization.contract.AuthorizationRequest;
import com.nexusphere.authorization.contract.ResourceOwner;
import com.nexusphere.authorization.domain.model.Role;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.Identifier;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.reference.ResourceReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/authorization")
class AuthorizationController {

    record ResourceBody(@NotBlank String type, @NotBlank String id, String networkId) {
    }

    record OwnerBody(String organizationId, String identityId) {
    }

    record EvaluateRequest(@NotBlank String action, @Valid ResourceBody resource, OwnerBody owner,
                           String delegationId, String capabilityTypeCode) {
    }

    record DecisionResponse(String decisionId, String result, String reason, String matchedRole, String principalId,
                            String action, String networkId, String targetNetworkId, String resourceType,
                            String resourceId, String delegationId, String federationId, String trustRelationshipId,
                            Instant decidedAt) {

        static DecisionResponse of(AuthorizationDecision decision) {
            return new DecisionResponse(decision.id().toString(), decision.result(), decision.reason(),
                    decision.matchedRole(), decision.principalId().toString(), decision.action(),
                    decision.networkId().toString(), decision.targetNetworkId().toString(), decision.resourceType(),
                    decision.resourceId(), text(decision.delegationId()), text(decision.federationId()),
                    text(decision.trustRelationshipId()), decision.decidedAt());
        }

        private static String text(UUID value) {
            return value == null ? null : value.toString();
        }
    }

    record RoleResponse(String role, List<String> actions) {
    }

    private final AuthorizationService authorization;

    AuthorizationController(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @PostMapping("/evaluate")
    DecisionResponse evaluate(@Valid @RequestBody EvaluateRequest request, PrincipalContext principal,
                              ExecutionContext context) {
        ResourceReference resource = request.resource() == null ? null : new ResourceReference(
                request.resource().type(), request.resource().id(), request.resource().networkId() == null
                ? principal.networkId() : NetworkId.of(request.resource().networkId()));
        ResourceOwner owner = request.owner() == null ? null : new ResourceOwner(
                request.owner().organizationId() == null ? null : OrganizationId.of(request.owner().organizationId()),
                request.owner().identityId() == null ? null : IdentityId.of(request.owner().identityId()));
        AuthorizationRequest authorizationRequest = new AuthorizationRequest(principal, request.action(), resource,
                owner, request.delegationId() == null ? null : Identifier.parse(request.delegationId(), "DelegationId"),
                request.capabilityTypeCode());
        return DecisionResponse.of(authorization.authorize(authorizationRequest, context));
    }

    @GetMapping("/decisions/{decisionId}")
    DecisionResponse decision(PrincipalContext principal, @PathVariable String decisionId) {
        return DecisionResponse.of(authorization.decision(principal, Identifier.parse(decisionId, "DecisionId")));
    }

    @GetMapping("/roles")
    List<RoleResponse> roles() {
        return Arrays.stream(Role.values())
                .map(role -> new RoleResponse(role.name(), role.actions().stream().sorted().toList())).toList();
    }
}
