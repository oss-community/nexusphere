package com.nexusphere.integration.application;

import com.nexusphere.authorization.contract.AuthorizationDecision;
import com.nexusphere.authorization.contract.AuthorizationDenied;
import com.nexusphere.authorization.contract.AuthorizationGranted;
import com.nexusphere.authorization.contract.DelegationEvidence;
import com.nexusphere.authorization.contract.DelegationEvidencePort;
import com.nexusphere.delegation.contract.DelegationGranted;
import com.nexusphere.delegation.contract.DelegationResumed;
import com.nexusphere.delegation.contract.DelegationRevoked;
import com.nexusphere.delegation.contract.DelegationSuspended;
import com.nexusphere.integration.domain.model.OutboxKind;
import com.nexusphere.integration.domain.model.OutboxMessage;
import com.nexusphere.integration.domain.repository.LedgerOutbox;
import com.nexusphere.shared.event.EventEnvelope;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
class LedgerEvidenceRecorder {

    private final LedgerProperties properties;
    private final LedgerOutbox outbox;
    private final DelegationEvidencePort delegations;
    private final JsonMapper json;
    private final TimeProvider time;

    LedgerEvidenceRecorder(LedgerProperties properties, LedgerOutbox outbox, DelegationEvidencePort delegations,
                           JsonMapper json, TimeProvider time) {
        this.properties = properties;
        this.outbox = outbox;
        this.delegations = delegations;
        this.json = json;
        this.time = time;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(EventEnvelope envelope) {
        if (!properties.enabled()) {
            return;
        }
        switch (envelope.payload()) {
            case AuthorizationGranted granted -> evidence(decision(envelope, granted.decision()));
            case AuthorizationDenied denied -> evidence(decision(envelope, denied.decision()));
            case DelegationGranted granted -> {
                evidence(delegation(envelope, "delegation/grant", granted.delegationId(),
                        granted.delegationNetworkId(), granted.delegatorPrincipalId(), granted.delegatePrincipalId()));
                grant(granted.delegationId());
            }
            case DelegationResumed resumed -> {
                evidence(delegation(envelope, "delegation/resume", resumed.delegationId(),
                        resumed.delegationNetworkId(), resumed.delegatorPrincipalId(), resumed.delegatePrincipalId()));
                grant(resumed.delegationId());
            }
            case DelegationRevoked revoked -> {
                evidence(delegation(envelope, "delegation/revoke", revoked.delegationId(),
                        revoked.delegationNetworkId(), revoked.delegatorPrincipalId(), revoked.delegatePrincipalId()));
                revoke(revoked.delegationId(), "Nexusphere delegation " + revoked.delegationId() + " was revoked");
            }
            case DelegationSuspended suspended -> {
                evidence(delegation(envelope, "delegation/suspend", suspended.delegationId(),
                        suspended.delegationNetworkId(), suspended.delegatorPrincipalId(),
                        suspended.delegatePrincipalId()));
                revoke(suspended.delegationId(),
                        "Nexusphere delegation " + suspended.delegationId() + " was suspended");
            }
            default -> {
                if (envelope.principalId() != null) {
                    evidence(generic(envelope));
                }
            }
        }
    }

    private Map<String, Object> decision(EventEnvelope envelope, AuthorizationDecision decision) {
        PrincipalId principal = decision.principalId();
        if (decision.delegationId() != null) {
            principal = delegations.find(decision.delegationId()).map(DelegationEvidence::delegatorPrincipalId)
                    .orElse(principal);
        }
        Map<String, Object> evidence = base(envelope, decision.principalId(), principal, decision.action());
        String target = decision.resourceType() == null ? null
                : decision.resourceId() == null ? decision.resourceType()
                : decision.resourceType() + "/" + decision.resourceId();
        evidence.put("target", target);
        evidence.put("decision", decision.allowed() ? "ALLOW" : "DENY");
        evidence.put("outcome", decision.allowed() ? "SUCCEEDED" : "DENIED");
        evidence.put("reason", decision.reason());
        if (decision.delegationId() != null) {
            evidence.put("delegationId", decision.delegationId().toString());
        }
        Map<String, String> attributes = attributes(envelope, decision.networkId());
        attributes.put("core.decisionId", decision.id().toString());
        attributes.put("core.targetNetwork", decision.targetNetworkId().toString());
        if (decision.matchedRole() != null) {
            attributes.put("core.matchedRole", decision.matchedRole());
        }
        evidence.put("attributes", attributes);
        return evidence;
    }

    private Map<String, Object> delegation(EventEnvelope envelope, String action, UUID delegationId,
                                           NetworkId network, PrincipalId delegator, PrincipalId delegate) {
        Map<String, Object> evidence = base(envelope, delegate, delegator, action);
        evidence.put("target", "delegation/" + delegationId);
        evidence.put("outcome", "SUCCEEDED");
        evidence.put("delegationId", delegationId.toString());
        evidence.put("attributes", attributes(envelope, network));
        return evidence;
    }

    private Map<String, Object> generic(EventEnvelope envelope) {
        Map<String, Object> evidence = base(envelope, envelope.principalId(), envelope.principalId(),
                envelope.eventType());
        evidence.put("outcome", "SUCCEEDED");
        evidence.put("attributes", attributes(envelope, envelope.networkId()));
        return evidence;
    }

    private static Map<String, Object> base(EventEnvelope envelope, PrincipalId agent, PrincipalId principal,
                                            String action) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("occurredAt", envelope.timestamp().toString());
        evidence.put("agentId", agent.value().toString());
        evidence.put("principalId", principal.value().toString());
        evidence.put("action", action);
        evidence.put("correlationId", envelope.correlationId().value());
        return evidence;
    }

    private static Map<String, String> attributes(EventEnvelope envelope, NetworkId network) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("core.event", envelope.eventType());
        attributes.put("core.eventId", envelope.eventId().toString());
        if (network != null) {
            attributes.put("core.network", network.toString());
        }
        return attributes;
    }

    private void grant(UUID delegationId) {
        delegations.find(delegationId).ifPresent(d -> {
            Map<String, Object> grant = new LinkedHashMap<>();
            grant.put("delegationId", d.id().toString());
            grant.put("networkId", d.networkId().toString());
            grant.put("principalId", d.delegatorPrincipalId().value().toString());
            grant.put("agentId", d.delegatePrincipalId().value().toString());
            grant.put("actions", d.actions().stream().sorted().toList());
            grant.put("targets", d.resourceTypes().isEmpty() ? List.of("*")
                    : d.resourceTypes().stream().sorted().map(type -> type + "/*").toList());
            grant.put("validFrom", d.validFrom().toString());
            grant.put("validUntil", d.validUntil() == null ? null : d.validUntil().toString());
            append(OutboxKind.GRANT, grant);
        });
    }

    private void revoke(UUID delegationId, String reason) {
        append(OutboxKind.REVOKE, Map.of("delegationId", delegationId.toString(), "reason", reason));
    }

    private void evidence(Map<String, Object> evidence) {
        append(OutboxKind.EVIDENCE, evidence);
    }

    private void append(OutboxKind kind, Map<String, Object> payload) {
        outbox.append(OutboxMessage.pending(kind, json.writeValueAsString(payload), time.now()));
    }
}
