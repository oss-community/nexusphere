package com.nexusphere.ledger.authorization.api.rest;

import com.nexusphere.ledger.authorization.api.rest.GrantController.CreateGrantRequest;
import com.nexusphere.ledger.authorization.api.rest.GrantController.GrantPage;
import com.nexusphere.ledger.authorization.api.rest.GrantController.GrantResponse;
import com.nexusphere.ledger.authorization.api.rest.GrantController.RevokeRequest;
import com.nexusphere.ledger.authorization.application.GrantService;
import com.nexusphere.ledger.authorization.domain.model.ConsentProof;
import com.nexusphere.ledger.authorization.domain.model.Grant;
import com.nexusphere.ledger.authorization.domain.model.GrantQuery;
import com.nexusphere.ledger.authorization.domain.model.GrantRequest;
import com.nexusphere.ledger.authorization.domain.model.GrantState;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.security.PrincipalIdentity;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/principal")
class PrincipalController {

    record PrincipalResponse(String principalId, String name, String issuer, String subject, Instant authTime,
                             Instant expiresAt) {
    }

    private final GrantService grants;
    private final Clock clock;

    PrincipalController(GrantService grants, Clock clock) {
        this.grants = grants;
        this.clock = clock;
    }

    @GetMapping
    PrincipalResponse whoami(Caller caller) {
        PrincipalIdentity p = caller.requirePrincipal();
        return new PrincipalResponse(p.principalId(), p.name(), p.issuer(), p.subject(), p.authTime(),
                p.expiresAt());
    }

    @GetMapping("/grants")
    GrantPage find(Caller caller,
                   @RequestParam(required = false) String state,
                   @RequestParam(defaultValue = "0") long after,
                   @RequestParam(defaultValue = "100") int limit) {
        PrincipalIdentity p = caller.requirePrincipal();
        List<Grant> page = grants.find(new GrantQuery(null, p.principalId(), state(state), after, limit));
        Instant now = clock.instant();
        Long nextAfter = page.size() == limit ? page.getLast().seq() : null;
        return new GrantPage(page.stream().map(g -> GrantResponse.of(g, now)).toList(), nextAfter);
    }

    @GetMapping("/grants/{id}")
    GrantResponse get(Caller caller, @PathVariable UUID id) {
        PrincipalIdentity p = caller.requirePrincipal();
        Grant grant = grants.get(id);
        if (!grant.terms().principalId().equals(p.principalId())) {
            throw LedgerException.notFound("Grant " + id);
        }
        return GrantResponse.of(grant, clock.instant());
    }

    @PostMapping("/grants")
    ResponseEntity<GrantResponse> create(Caller caller, @RequestBody CreateGrantRequest request) {
        PrincipalIdentity p = caller.requirePrincipal();
        String principalId = request.principalId() == null ? p.principalId() : request.principalId();
        Grant grant = grants.createByPrincipal(new GrantRequest(principalId, request.agentId(), request.actions(),
                request.targets(), request.notBefore(), request.expiresAt(), request.maxUses(), request.reason()),
                proof(p));
        return ResponseEntity.created(URI.create("/api/v1/principal/grants/" + grant.terms().id()))
                .body(GrantResponse.of(grant, clock.instant()));
    }

    @PostMapping("/grants/{id}/approve")
    GrantResponse approve(Caller caller, @PathVariable UUID id) {
        return GrantResponse.of(grants.approve(id, proof(caller.requirePrincipal())), clock.instant());
    }

    @PostMapping("/grants/{id}/deny")
    GrantResponse deny(Caller caller, @PathVariable UUID id, @RequestBody(required = false) RevokeRequest request) {
        return GrantResponse.of(grants.deny(id, proof(caller.requirePrincipal()),
                request == null ? null : request.reason()), clock.instant());
    }

    @PostMapping("/grants/{id}/revoke")
    GrantResponse revoke(Caller caller, @PathVariable UUID id, @RequestBody(required = false) RevokeRequest request) {
        return GrantResponse.of(grants.revokeByPrincipal(id, proof(caller.requirePrincipal()),
                request == null ? null : request.reason()), clock.instant());
    }

    private static ConsentProof proof(PrincipalIdentity p) {
        return new ConsentProof(p.principalId(), p.issuer(), p.subject(), p.tokenHash(), p.authTime());
    }

    private static GrantState state(String value) {
        if (value == null) {
            return null;
        }
        try {
            return GrantState.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw LedgerException.invalid("The query has invalid parameters.",
                    Map.of("state", "must be one of PENDING, ACTIVE, DENIED, REVOKED"));
        }
    }
}
