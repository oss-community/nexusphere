package com.nexusphere.ledger.authorization.api.rest;

import com.nexusphere.ledger.authorization.application.MandateService;
import com.nexusphere.ledger.authorization.domain.model.Mandate;
import com.nexusphere.ledger.authorization.domain.model.MandateRequest;
import com.nexusphere.ledger.server.security.Caller;
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
@RequestMapping("/api/v1/mandates")
class MandateController {

    record IssueMandateRequest(UUID grantId, String audience, Instant expiresAt) {
    }

    record RevokeRequest(String reason) {
    }

    record MandateResponse(UUID id, UUID grantId, String agentId, String principalId, String audience,
                           long statusIndex, Instant issuedAt, Instant expiresAt, String status, Instant revokedAt,
                           String revokeReason, String token) {

        static MandateResponse of(Mandate m, Instant now) {
            return new MandateResponse(m.id(), m.grantId(), m.agentId(), m.principalId(), m.audience(),
                    m.statusIndex(), m.issuedAt(), m.expiresAt(), m.status(now), m.revokedAt(), m.revokeReason(),
                    m.token());
        }
    }

    private final MandateService mandates;
    private final Clock clock;

    MandateController(MandateService mandates, Clock clock) {
        this.mandates = mandates;
        this.clock = clock;
    }

    @PostMapping
    ResponseEntity<MandateResponse> issue(Caller caller, @RequestBody IssueMandateRequest request) {
        Mandate mandate = mandates.issue(caller,
                new MandateRequest(request.grantId(), request.audience(), request.expiresAt()));
        return ResponseEntity.created(URI.create("/api/v1/mandates/" + mandate.id()))
                .body(MandateResponse.of(mandate, clock.instant()));
    }

    @GetMapping("/{id}")
    MandateResponse get(Caller caller, @PathVariable UUID id) {
        return MandateResponse.of(mandates.get(caller, id), clock.instant());
    }

    @GetMapping
    List<MandateResponse> forGrant(Caller caller, @RequestParam UUID grantId) {
        Instant now = clock.instant();
        return mandates.forGrant(caller, grantId).stream().map(m -> MandateResponse.of(m, now)).toList();
    }

    @PostMapping("/{id}/revoke")
    MandateResponse revoke(Caller caller, @PathVariable UUID id, @RequestBody(required = false) RevokeRequest request) {
        caller.requireOperator();
        return MandateResponse.of(mandates.revoke(id, request == null ? null : request.reason()), clock.instant());
    }
}
