package com.nexusphere.integration.application;

import com.nexusphere.authorization.contract.DelegationEvidence;
import com.nexusphere.authorization.contract.DelegationEvidencePort;
import com.nexusphere.integration.domain.model.LedgerGrant;
import com.nexusphere.integration.domain.repository.LedgerOutbox;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.NotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class LedgerMandates {

    private final LedgerProperties properties;
    private final LedgerOutbox outbox;
    private final LedgerGateway ledger;
    private final DelegationEvidencePort delegations;

    LedgerMandates(LedgerProperties properties, LedgerOutbox outbox, LedgerGateway ledger,
                   DelegationEvidencePort delegations) {
        this.properties = properties;
        this.outbox = outbox;
        this.ledger = ledger;
        this.delegations = delegations;
    }

    public Map<String, Object> issue(PrincipalContext principal, UUID delegationId, String audience,
                                     Instant expiresAt) {
        if (!properties.enabled()) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "LEDGER_NOT_CONFIGURED",
                    "This Nexusphere is not connected to a ledger");
        }
        DelegationEvidence delegation = delegations.find(delegationId)
                .filter(d -> d.networkId().equals(principal.networkId()))
                .orElseThrow(() -> new NotFoundException("Delegation", delegationId));
        if (!principal.principalId().equals(delegation.delegatePrincipalId())
                && !principal.principalId().equals(delegation.delegatorPrincipalId())) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "NOT_DELEGATION_PARTY",
                    "Only the delegator or the delegate may ask for a mandate");
        }
        if (!"ACTIVE".equals(delegation.status())) {
            throw new ConflictException("DELEGATION_NOT_ACTIVE",
                    "Delegation " + delegationId + " is " + delegation.status());
        }
        LedgerGrant grant = outbox.activeGrant(delegationId)
                .orElseThrow(() -> new ConflictException("DELEGATION_NOT_IN_LEDGER",
                        "Delegation " + delegationId + " has no active grant in the ledger yet"));
        try {
            return ledger.issueMandate(grant.grantId(), audience, expiresAt == null ? null : expiresAt.toString());
        } catch (LedgerGateway.Unavailable e) {
            if (e.status() >= 400 && e.status() < 500) {
                throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "LEDGER_REFUSED", e.getMessage());
            }
            throw new DomainException(ErrorCategory.INFRASTRUCTURE_ERROR, "LEDGER_UNAVAILABLE", e.getMessage());
        }
    }
}
