package com.nexusphere.ledger.authorization.application;

import com.nexusphere.ledger.agent.application.AgentService;
import com.nexusphere.ledger.agent.domain.model.Agent;
import com.nexusphere.ledger.authorization.domain.model.Grant;
import com.nexusphere.ledger.authorization.domain.model.GrantQuery;
import com.nexusphere.ledger.authorization.domain.model.GrantRequest;
import com.nexusphere.ledger.authorization.domain.model.GrantState;
import com.nexusphere.ledger.authorization.domain.repository.GrantRepository;
import com.nexusphere.ledger.chain.GrantTerms;
import com.nexusphere.ledger.chain.Timestamps;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.web.FieldErrors;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GrantService {

    public static final int MAX_PAGE_SIZE = 500;
    static final int MAX_ACTIONS = 32;
    static final int MAX_TARGETS = 64;
    static final long MAX_USES = 1_000_000_000L;
    static final Duration MAX_LIFETIME = Duration.ofDays(366);

    private final GrantRepository grants;
    private final AgentService agents;
    private final EvidenceService evidence;
    private final Clock clock;

    GrantService(GrantRepository grants, AgentService agents, EvidenceService evidence, Clock clock) {
        this.grants = grants;
        this.agents = agents;
        this.evidence = evidence;
        this.clock = clock;
    }

    @Transactional
    public Grant create(GrantRequest request) {
        Instant now = Timestamps.normalize(clock.instant());
        validate(request, now);
        Agent agent = agents.get(request.agentId());
        if (!agent.active()) {
            throw LedgerException.conflict("AGENT_NOT_ACTIVE", "Agent " + agent.agentId() + " is disabled.");
        }
        GrantTerms terms = new GrantTerms(UUID.randomUUID(), request.principalId(), request.agentId(),
                request.actions(), request.targets(), request.notBefore(), request.expiresAt(), request.maxUses(), now);
        grants.insert(terms, request.reason());
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("expiresAt", Timestamps.format(terms.expiresAt()));
        if (terms.notBefore() != null) {
            attributes.put("notBefore", Timestamps.format(terms.notBefore()));
        }
        if (terms.maxUses() != null) {
            attributes.put("maxUses", terms.maxUses().toString());
        }
        record(terms, "grant/create", request.reason(), attributes);
        return get(terms.id());
    }

    @Transactional
    public Grant revoke(UUID id, String reason) {
        new FieldErrors().text("reason", reason, false, 500).throwIfAny("The revocation has invalid fields.");
        Grant grant = grants.lock(id).orElseThrow(() -> LedgerException.notFound("Grant " + id));
        if (grant.state() == GrantState.REVOKED) {
            return grant;
        }
        grants.revoke(id, clock.instant(), reason);
        record(grant.terms(), "grant/revoke", reason, Map.of());
        return get(id);
    }

    @Transactional(readOnly = true)
    public Grant get(UUID id) {
        return grants.find(id).orElseThrow(() -> LedgerException.notFound("Grant " + id));
    }

    @Transactional(readOnly = true)
    public List<Grant> find(GrantQuery query) {
        Map<String, Object> errors = new LinkedHashMap<>();
        if (query.afterSeq() < 0) {
            errors.put("after", "must not be negative");
        }
        if (query.limit() < 1 || query.limit() > MAX_PAGE_SIZE) {
            errors.put("limit", "must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (!errors.isEmpty()) {
            throw LedgerException.invalid("The query has invalid parameters.", errors);
        }
        return grants.find(query);
    }

    private void record(GrantTerms terms, String action, String reason, Map<String, String> attributes) {
        evidence.record(new EvidenceSubmission(clock.instant(), terms.agentId(), terms.principalId(), action,
                terms.id().toString(), null, reason, terms.id().toString(), terms.hash(), null, Outcome.SUCCEEDED,
                null, attributes));
    }

    private static void validate(GrantRequest r, Instant now) {
        FieldErrors errors = new FieldErrors()
                .text("principalId", r.principalId(), true, 200)
                .text("agentId", r.agentId(), true, 200)
                .text("reason", r.reason(), false, 500);
        patterns(errors, "actions", r.actions(), MAX_ACTIONS, 120);
        patterns(errors, "targets", r.targets(), MAX_TARGETS, 300);
        if (r.expiresAt() == null) {
            errors.reject("expiresAt", "is required");
        } else if (!r.expiresAt().isAfter(now)) {
            errors.reject("expiresAt", "must be in the future");
        } else if (r.expiresAt().isAfter(now.plus(MAX_LIFETIME))) {
            errors.reject("expiresAt", "must be at most " + MAX_LIFETIME.toDays() + " days ahead");
        }
        if (r.notBefore() != null && r.expiresAt() != null && !r.notBefore().isBefore(r.expiresAt())) {
            errors.reject("notBefore", "must be before expiresAt");
        }
        if (r.maxUses() != null && (r.maxUses() < 1 || r.maxUses() > MAX_USES)) {
            errors.reject("maxUses", "must be between 1 and " + MAX_USES);
        }
        errors.throwIfAny("The grant has invalid fields.");
    }

    private static void patterns(FieldErrors errors, String field, List<String> values, int max, int maxLength) {
        if (values == null || values.isEmpty()) {
            errors.reject(field, "must have at least one entry");
            return;
        }
        if (values.size() > max) {
            errors.reject(field, "must have at most " + max + " entries");
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            errors.text(field + "[" + i + "]", value, true, maxLength);
            if (value != null && value.indexOf('*') >= 0 && value.indexOf('*') != value.length() - 1) {
                errors.reject(field + "[" + i + "]", "may only use * as the last character");
            }
        }
    }
}
