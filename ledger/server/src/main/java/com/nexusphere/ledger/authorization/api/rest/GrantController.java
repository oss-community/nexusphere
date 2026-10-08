package com.nexusphere.ledger.authorization.api.rest;

import com.nexusphere.ledger.authorization.application.GrantService;
import com.nexusphere.ledger.authorization.domain.model.Grant;
import com.nexusphere.ledger.authorization.domain.model.GrantQuery;
import com.nexusphere.ledger.authorization.domain.model.GrantRequest;
import com.nexusphere.ledger.chain.GrantTerms;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/grants")
class GrantController {

    record CreateGrantRequest(String principalId, String agentId, List<String> actions, List<String> targets,
                              Instant notBefore, Instant expiresAt, Long maxUses, String reason) {
    }

    record RevokeRequest(String reason) {
    }

    record GrantResponse(String format, UUID id, String principalId, String agentId, List<String> actions,
                         List<String> targets, Instant notBefore, Instant expiresAt, Long maxUses, Instant createdAt,
                         String termsHash, long uses, String status, String reason, Instant revokedAt,
                         String revokeReason, String consent, Instant consentedAt) {

        static GrantResponse of(Grant g, Instant now) {
            GrantTerms t = g.terms();
            return new GrantResponse(GrantTerms.FORMAT, t.id(), t.principalId(), t.agentId(), t.actions(),
                    t.targets(), t.notBefore(), t.expiresAt(), t.maxUses(), t.createdAt(), t.hash(), g.uses(),
                    g.status(now).name(), g.reason(), g.revokedAt(), g.revokeReason(),
                    g.consent() == null ? null : g.consent().name(), g.consentedAt());
        }
    }

    record GrantPage(List<GrantResponse> items, Long nextAfter) {
    }

    private final GrantService grants;
    private final Clock clock;

    GrantController(GrantService grants, Clock clock) {
        this.grants = grants;
        this.clock = clock;
    }

    @PostMapping
    ResponseEntity<GrantResponse> create(Caller caller, @RequestBody CreateGrantRequest request) {
        if (!caller.isOperator()) {
            if (!grants.consentRequired()) {
                caller.requireOperator();
            }
            if (!caller.canActAs(request.agentId())) {
                throw LedgerException.forbidden("An agent may only ask for grants for itself.");
            }
        }
        Grant grant = grants.create(new GrantRequest(request.principalId(), request.agentId(), request.actions(),
                request.targets(), request.notBefore(), request.expiresAt(), request.maxUses(), request.reason()));
        return ResponseEntity.created(URI.create("/api/v1/grants/" + grant.terms().id()))
                .body(GrantResponse.of(grant, clock.instant()));
    }

    @PostMapping("/{id}/revoke")
    GrantResponse revoke(Caller caller, @PathVariable UUID id, @RequestBody(required = false) RevokeRequest request) {
        caller.requireOperator();
        return GrantResponse.of(grants.revoke(id, request == null ? null : request.reason()), clock.instant());
    }

    @GetMapping("/{id}")
    GrantResponse get(Caller caller, @PathVariable UUID id) {
        Grant grant = grants.get(id);
        if (!caller.canActAs(grant.terms().agentId())) {
            throw LedgerException.notFound("Grant " + id);
        }
        return GrantResponse.of(grant, clock.instant());
    }

    @GetMapping
    GrantPage find(Caller caller,
                   @RequestParam(required = false) String agentId,
                   @RequestParam(required = false) String principalId,
                   @RequestParam(defaultValue = "0") long after,
                   @RequestParam(defaultValue = "100") int limit) {
        if (!caller.isOperator()) {
            if (agentId != null && !caller.canActAs(agentId)) {
                throw LedgerException.forbidden("An agent may only read its own grants.");
            }
            agentId = caller.agentId();
        }
        List<Grant> page = grants.find(new GrantQuery(agentId, principalId, null, after, limit));
        Instant now = clock.instant();
        Long nextAfter = page.size() == limit ? page.getLast().seq() : null;
        return new GrantPage(page.stream().map(g -> GrantResponse.of(g, now)).toList(), nextAfter);
    }
}
