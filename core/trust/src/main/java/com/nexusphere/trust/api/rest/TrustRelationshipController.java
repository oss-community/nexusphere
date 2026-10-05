package com.nexusphere.trust.api.rest;

import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.Identifier;
import com.nexusphere.shared.time.TimeProvider;
import com.nexusphere.trust.application.TrustService;
import com.nexusphere.trust.domain.model.Party;
import com.nexusphere.trust.domain.model.TrustRelationship;
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
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/trust-relationships")
class TrustRelationshipController {

    record PartyBody(String type, String id, String networkId) {

        TrustService.PartyInput input() {
            return new TrustService.PartyInput(type, id, networkId);
        }

        static PartyBody of(Party party) {
            return new PartyBody(party.type().name(), party.id().toString(), party.networkId().toString());
        }
    }

    record EstablishTrustRequest(PartyBody source, PartyBody target, List<String> scopes, String level,
                                 Instant effectiveFrom, Instant effectiveUntil) {
    }

    record TrustResponse(String id, PartyBody source, PartyBody target, Set<String> scopes, String level,
                         String status, boolean effective, Instant effectiveFrom, Instant effectiveUntil,
                         Instant createdAt, Instant revokedAt) {

        static TrustResponse of(TrustRelationship trust, Instant now) {
            return new TrustResponse(trust.id().toString(), PartyBody.of(trust.source()), PartyBody.of(trust.target()),
                    trust.scopes(), trust.level().name(), trust.status().name(), trust.isEffective(now),
                    trust.effectiveFrom(), trust.effectiveUntil().orElse(null), trust.createdAt(),
                    trust.revokedAt().orElse(null));
        }
    }

    record EvaluationResponse(boolean trusted, String scope, String trustRelationshipId) {
    }

    private final TrustService trust;
    private final TimeProvider time;

    TrustRelationshipController(TrustService trust, TimeProvider time) {
        this.trust = trust;
        this.time = time;
    }

    @PostMapping
    ResponseEntity<TrustResponse> establish(@PathVariable String networkId, @RequestBody EstablishTrustRequest request,
                                            PrincipalContext principal, ExecutionContext context) {
        TrustRelationship established = trust.establish(principal, new TrustService.Establishment(
                request.source() == null ? null : request.source().input(),
                request.target() == null ? null : request.target().input(), request.scopes(), request.level(),
                request.effectiveFrom(), request.effectiveUntil()), context);
        return ResponseEntity.created(URI.create("/api/v1/networks/" + networkId + "/trust-relationships/"
                + established.id())).body(TrustResponse.of(established, time.now()));
    }

    @GetMapping
    List<TrustResponse> list(PrincipalContext principal, @RequestParam(required = false) String direction) {
        Instant now = time.now();
        return trust.list(principal, direction(direction)).stream().map(found -> TrustResponse.of(found, now))
                .toList();
    }

    @GetMapping("/{trustId}")
    TrustResponse get(PrincipalContext principal, @PathVariable String trustId) {
        return TrustResponse.of(trust.get(principal, Identifier.parse(trustId, "TrustRelationshipId")), time.now());
    }

    @PostMapping("/{trustId}/revoke")
    TrustResponse revoke(PrincipalContext principal, @PathVariable String trustId, ExecutionContext context) {
        return TrustResponse.of(trust.revoke(principal, Identifier.parse(trustId, "TrustRelationshipId"), context),
                time.now());
    }

    @GetMapping("/evaluation")
    EvaluationResponse evaluate(PrincipalContext principal,
                                @RequestParam(required = false) String sourceType,
                                @RequestParam(required = false) String sourceId,
                                @RequestParam(required = false) String sourceNetworkId,
                                @RequestParam String targetType, @RequestParam String targetId,
                                @RequestParam(required = false) String targetNetworkId,
                                @RequestParam String scope) {
        TrustService.PartyInput source = sourceType == null ? null
                : new TrustService.PartyInput(sourceType, sourceId, sourceNetworkId);
        TrustService.Evaluation evaluation = trust.evaluate(principal, source,
                new TrustService.PartyInput(targetType, targetId, targetNetworkId), scope);
        return new EvaluationResponse(evaluation.trusted(), scope,
                evaluation.trustRelationshipId() == null ? null : evaluation.trustRelationshipId().toString());
    }

    private static TrustService.Direction direction(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(TrustService.Direction.values())
                .filter(direction -> direction.name().equalsIgnoreCase(value.trim())).findFirst()
                .orElseThrow(() -> new ValidationException("INVALID_DIRECTION",
                        "The direction must be one of " + Arrays.toString(TrustService.Direction.values())));
    }
}
