package com.nexusphere.federation.api.rest;

import com.nexusphere.federation.application.FederationService;
import com.nexusphere.federation.domain.model.Federation;
import com.nexusphere.federation.domain.model.FederationScope;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.id.Identifier;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.time.TimeProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/federations")
class FederationController {

    record ProposeFederationRequest(@NotBlank String partnerNetworkId, List<String> scopes, Instant effectiveUntil) {
    }

    record TransitionRequest(Long version) {
    }

    record FederationResponse(String id, String proposerNetworkId, String partnerNetworkId, List<String> scopes,
                              String status, boolean active, Instant effectiveFrom, Instant effectiveUntil,
                              String suspendedBy, Instant createdAt, Instant updatedAt, Long version) {

        static FederationResponse of(Federation federation, Instant now) {
            return new FederationResponse(federation.id().toString(), federation.proposerNetworkId().toString(),
                    federation.partnerNetworkId().toString(),
                    federation.scopes().stream().map(FederationScope::name).toList(), federation.status().name(),
                    federation.isActive(now), federation.effectiveFrom().orElse(null),
                    federation.effectiveUntil().orElse(null),
                    federation.suspendedBy().map(NetworkId::toString).orElse(null), federation.createdAt(),
                    federation.updatedAt(), federation.version());
        }
    }

    private final FederationService federations;
    private final TimeProvider time;

    FederationController(FederationService federations, TimeProvider time) {
        this.federations = federations;
        this.time = time;
    }

    @PostMapping
    ResponseEntity<FederationResponse> propose(@PathVariable String networkId,
                                               @Valid @RequestBody ProposeFederationRequest request,
                                               PrincipalContext principal, ExecutionContext context) {
        Federation federation = federations.propose(principal, new FederationService.Proposal(
                request.partnerNetworkId(), request.scopes(), request.effectiveUntil()), context);
        return ResponseEntity.created(URI.create("/api/v1/networks/" + networkId + "/federations/" + federation.id()))
                .body(FederationResponse.of(federation, time.now()));
    }

    @GetMapping
    List<FederationResponse> list(PrincipalContext principal) {
        Instant now = time.now();
        return federations.list(principal).stream().map(federation -> FederationResponse.of(federation, now)).toList();
    }

    @GetMapping("/{federationId}")
    FederationResponse get(PrincipalContext principal, @PathVariable String federationId) {
        return FederationResponse.of(federations.get(principal, Identifier.parse(federationId, "FederationId")),
                time.now());
    }

    @PostMapping("/{federationId}/{action:submit|accept|reject|suspend|resume|terminate}")
    FederationResponse transition(PrincipalContext principal, @PathVariable String federationId,
                                  @PathVariable String action, @RequestBody(required = false) TransitionRequest request,
                                  ExecutionContext context) {
        Federation federation = federations.apply(principal, Identifier.parse(federationId, "FederationId"),
                FederationService.Action.valueOf(action.toUpperCase(Locale.ROOT)),
                request == null ? null : request.version(), context);
        return FederationResponse.of(federation, time.now());
    }
}
