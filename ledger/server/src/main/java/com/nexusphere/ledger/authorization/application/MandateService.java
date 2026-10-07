package com.nexusphere.ledger.authorization.application;

import com.nexusphere.ledger.authorization.domain.model.Grant;
import com.nexusphere.ledger.authorization.domain.model.GrantStatus;
import com.nexusphere.ledger.authorization.domain.model.Mandate;
import com.nexusphere.ledger.authorization.domain.model.MandateRequest;
import com.nexusphere.ledger.authorization.domain.repository.GrantRepository;
import com.nexusphere.ledger.authorization.domain.repository.MandateRepository;
import com.nexusphere.ledger.chain.GrantTerms;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.mandate.MandateClaims;
import com.nexusphere.ledger.mandate.StatusList;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.server.web.FieldErrors;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MandateService {

    public static final String STATUS_LIST_PATH = "/public/v1/mandates/status";

    private final MandateRepository mandates;
    private final GrantRepository grants;
    private final EvidenceService evidence;
    private final LedgerSigner signer;
    private final LedgerProperties properties;
    private final Clock clock;

    MandateService(MandateRepository mandates, GrantRepository grants, EvidenceService evidence, LedgerSigner signer,
                   LedgerProperties properties, Clock clock) {
        this.mandates = mandates;
        this.grants = grants;
        this.evidence = evidence;
        this.signer = signer;
        this.properties = properties;
        this.clock = clock;
    }

    public String issuer() {
        String issuer = properties.mandate() == null ? null : properties.mandate().issuer();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("ledger.mandate.issuer must be set");
        }
        return issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }

    @Transactional
    public Mandate issue(Caller caller, MandateRequest request) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        FieldErrors errors = new FieldErrors().text("audience", request.audience(), false, 300);
        if (request.grantId() == null) {
            errors.reject("grantId", "is required");
        }
        errors.throwIfAny("The mandate request has invalid fields.");
        Grant grant = grants.lock(request.grantId())
                .filter(g -> caller.canActAs(g.terms().agentId()))
                .orElseThrow(() -> LedgerException.notFound("Grant " + request.grantId()));
        GrantStatus status = grant.status(now);
        if (status != GrantStatus.ACTIVE && status != GrantStatus.NOT_YET_VALID) {
            throw LedgerException.conflict("GRANT_NOT_ACTIVE", "Grant " + request.grantId() + " is " + status + ".");
        }
        GrantTerms terms = grant.terms();
        Instant grantEnd = terms.expiresAt().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = request.expiresAt() == null ? grantEnd : request.expiresAt().truncatedTo(ChronoUnit.SECONDS);
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(grantEnd)) {
            throw LedgerException.invalid("The mandate request has invalid fields.",
                    Map.of("expiresAt", "must be in the future and not after the grant expires"));
        }
        Instant notBefore = terms.notBefore() != null && terms.notBefore().isAfter(now)
                ? terms.notBefore().truncatedTo(ChronoUnit.SECONDS) : now;
        UUID id = UUID.randomUUID();
        long index = mandates.reserve(id, terms.id(), terms.agentId(), terms.principalId(), request.audience(), now,
                expiresAt);
        if (index >= StatusList.DEFAULT_SIZE) {
            throw LedgerException.conflict("STATUS_LIST_FULL", "The mandate status list is full.");
        }
        MandateClaims claims = new MandateClaims(issuer(), id, terms.agentId(), terms.principalId(),
                request.audience(), terms.actions(), terms.targets(), terms.maxUses(), terms.id(), terms.hash(), now,
                notBefore, expiresAt, issuer() + STATUS_LIST_PATH, index);
        String token = signer.signMandate(claims);
        mandates.attachToken(id, token);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("statusIndex", String.valueOf(index));
        attributes.put("expiresAt", expiresAt.toString());
        if (request.audience() != null) {
            attributes.put("audience", request.audience());
        }
        record(terms, id, "mandate/issue", null, Hashes.sha256(token.getBytes(StandardCharsets.US_ASCII)),
                attributes);
        return mandates.find(id).orElseThrow();
    }

    @Transactional
    public Mandate revoke(UUID id, String reason) {
        new FieldErrors().text("reason", reason, false, 500).throwIfAny("The revocation has invalid fields.");
        Mandate mandate = mandates.lock(id).orElseThrow(() -> LedgerException.notFound("Mandate " + id));
        if (mandate.revokedAt() == null) {
            revoke(mandate, reason);
        }
        return mandates.find(id).orElseThrow();
    }

    @Transactional
    public void revokeForGrant(UUID grantId, String reason) {
        mandates.lockActiveForGrant(grantId).forEach(mandate -> revoke(mandate, reason));
    }

    @Transactional(readOnly = true)
    public Mandate get(Caller caller, UUID id) {
        return mandates.find(id).filter(m -> caller.canActAs(m.agentId()))
                .orElseThrow(() -> LedgerException.notFound("Mandate " + id));
    }

    @Transactional(readOnly = true)
    public List<Mandate> forGrant(Caller caller, UUID grantId) {
        Grant grant = grants.find(grantId).filter(g -> caller.canActAs(g.terms().agentId()))
                .orElseThrow(() -> LedgerException.notFound("Grant " + grantId));
        return mandates.findByGrant(grant.terms().id());
    }

    @Transactional(readOnly = true)
    public String statusList() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Duration ttl = properties.mandate() == null || properties.mandate().statusListTtl() == null
                ? Duration.ofMinutes(5) : properties.mandate().statusListTtl();
        return signer.signStatusList(new StatusList(issuer(), issuer() + STATUS_LIST_PATH, now, now.plus(ttl),
                StatusList.DEFAULT_SIZE, mandates.revokedIndexes()));
    }

    private void revoke(Mandate mandate, String reason) {
        mandates.revoke(mandate.id(), clock.instant(), reason);
        Grant grant = grants.find(mandate.grantId()).orElseThrow();
        record(grant.terms(), mandate.id(), "mandate/revoke", reason, null,
                Map.of("statusIndex", String.valueOf(mandate.statusIndex())));
    }

    private void record(GrantTerms terms, UUID mandateId, String action, String reason, String inputHash,
                        Map<String, String> attributes) {
        evidence.record(new EvidenceSubmission(clock.instant(), terms.agentId(), terms.principalId(), action,
                mandateId.toString(), null, reason, terms.id().toString(), inputHash, null, Outcome.SUCCEEDED, null,
                attributes));
    }
}
