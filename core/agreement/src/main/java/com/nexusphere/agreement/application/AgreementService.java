package com.nexusphere.agreement.application;

import com.nexusphere.agreement.contract.AgreementDirectory;
import com.nexusphere.agreement.contract.AgreementSnapshot;
import com.nexusphere.agreement.domain.model.Actor;
import com.nexusphere.agreement.domain.model.Agreement;
import com.nexusphere.agreement.domain.model.AgreementParty;
import com.nexusphere.agreement.domain.repository.AgreementRepository;
import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.authorization.contract.AuthorizationDecision;
import com.nexusphere.authorization.contract.AuthorizationRequest;
import com.nexusphere.authorization.contract.Authorizer;
import com.nexusphere.discovery.contract.CapabilityDiscovery;
import com.nexusphere.discovery.contract.DiscoveredCapability;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.reference.ResourceReference;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class AgreementService implements AgreementDirectory {

    public record Draft(String capabilityId, String type, String title, Map<String, Object> terms, UUID delegationId) {
    }

    private final AgreementRepository agreements;
    private final CapabilityDiscovery discovery;
    private final Authorizer authorizer;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    AgreementService(AgreementRepository agreements, CapabilityDiscovery discovery, Authorizer authorizer,
                     DomainEventPublisher events, TimeProvider time) {
        this.agreements = agreements;
        this.discovery = discovery;
        this.authorizer = authorizer;
        this.events = events;
        this.time = time;
    }

    public Agreement create(PrincipalContext principal, Draft draft, ExecutionContext context) {
        if (draft.capabilityId() == null) {
            throw new ValidationException("CAPABILITY_REQUIRED", "The capability of the agreement must be specified");
        }
        if (principal.organizationId() == null) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "AGREEMENT_ACCOUNTABLE_PARTY_REQUIRED",
                    "The principal must act for an organization to propose an agreement");
        }
        CapabilityId capabilityId = CapabilityId.of(draft.capabilityId());
        DiscoveredCapability capability = discovery.find(principal, capabilityId, context)
                .orElseThrow(() -> new NotFoundException("Capability", capabilityId));
        if (capability.accountableOrganizationId() == null) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "CAPABILITY_WITHOUT_ACCOUNTABLE_PARTY",
                    "Capability " + capabilityId + " has no accountable organization");
        }
        UUID id = UUID.randomUUID();
        AuthorizationDecision decision = authorizer.require(AuthorizationRequest.of(principal,
                        Actions.AGREEMENT_PROPOSE, new ResourceReference("agreement", id.toString(),
                                capability.originNetworkId()))
                .withCapabilityType(capability.typeCode()).withDelegation(draft.delegationId()), context);
        Agreement agreement = Agreement.draft(id, draft.type(), draft.title(), capabilityId,
                capability.originNetworkId(), capability.typeCode(),
                new AgreementParty(principal.organizationId(), principal.networkId()),
                new AgreementParty(capability.accountableOrganizationId(), capability.originNetworkId()),
                draft.terms(), actor(principal, decision), time.now());
        return persist(agreement, context);
    }

    public Agreement propose(PrincipalContext principal, UUID id, Integer expectedVersion, UUID delegationId,
                             ExecutionContext context) {
        Agreement agreement = load(principal, id);
        Actor actor = authorize(principal, agreement, Actions.AGREEMENT_PROPOSE, delegationId, context);
        agreement.propose(expectedVersion, actor, time.now());
        return persist(agreement, context);
    }

    public Agreement revise(PrincipalContext principal, UUID id, Map<String, Object> terms, Integer expectedVersion,
                            UUID delegationId, ExecutionContext context) {
        Agreement agreement = load(principal, id);
        Actor actor = authorize(principal, agreement, Actions.AGREEMENT_PROPOSE, delegationId, context);
        agreement.revise(terms, expectedVersion, actor, time.now());
        return persist(agreement, context);
    }

    public Agreement accept(PrincipalContext principal, UUID id, int version, UUID delegationId,
                            ExecutionContext context) {
        Agreement agreement = load(principal, id);
        Actor actor = authorize(principal, agreement, Actions.AGREEMENT_ACCEPT, delegationId, context);
        agreement.accept(version, actor, time.now());
        return persist(agreement, context);
    }

    public Agreement reject(PrincipalContext principal, UUID id, int version, String reason, UUID delegationId,
                            ExecutionContext context) {
        Agreement agreement = load(principal, id);
        Actor actor = authorize(principal, agreement, Actions.AGREEMENT_ACCEPT, delegationId, context);
        agreement.reject(version, reason, actor, time.now());
        return persist(agreement, context);
    }

    public Agreement activate(PrincipalContext principal, UUID id, ExecutionContext context) {
        Agreement agreement = load(principal, id);
        agreement.activate(authorize(principal, agreement, Actions.AGREEMENT_MANAGE, null, context), time.now());
        return persist(agreement, context);
    }

    public Agreement complete(PrincipalContext principal, UUID id, ExecutionContext context) {
        Agreement agreement = load(principal, id);
        agreement.complete(authorize(principal, agreement, Actions.AGREEMENT_MANAGE, null, context), time.now());
        return persist(agreement, context);
    }

    public Agreement terminate(PrincipalContext principal, UUID id, String reason, ExecutionContext context) {
        Agreement agreement = load(principal, id);
        agreement.terminate(reason, authorize(principal, agreement, Actions.AGREEMENT_MANAGE, null, context),
                time.now());
        return persist(agreement, context);
    }

    @Transactional(readOnly = true)
    public Agreement get(PrincipalContext principal, UUID id) {
        return agreements.findById(id).filter(agreement -> readable(principal, agreement))
                .orElseThrow(() -> new NotFoundException("Agreement", id));
    }

    @Transactional(readOnly = true)
    public List<Agreement> list(PrincipalContext principal) {
        return agreements.findInvolving(principal.networkId()).stream()
                .filter(agreement -> readable(principal, agreement)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgreementSnapshot> find(UUID agreementId) {
        return agreements.findById(agreementId).map(agreement -> new AgreementSnapshot(agreement.id(),
                agreement.networkId(), agreement.type(), agreement.status().name(), agreement.current().number(),
                agreement.capabilityId(), agreement.capabilityNetworkId(), agreement.capabilityTypeCode(),
                agreement.proposer().organizationId(), agreement.proposer().networkId(),
                agreement.counterparty().organizationId(), agreement.counterparty().networkId()));
    }

    private boolean readable(PrincipalContext principal, Agreement agreement) {
        return agreement.involves(principal.networkId()) && (agreement.isParty(actor(principal, null))
                || authorizer.actionsHeldBy(principal).contains(Actions.AUDIT_READ));
    }

    private Agreement load(PrincipalContext principal, UUID id) {
        Agreement agreement = agreements.findById(id).filter(found -> found.involves(principal.networkId()))
                .orElseThrow(() -> new NotFoundException("Agreement", id));
        agreement.partyOf(actor(principal, null));
        return agreement;
    }

    private Actor authorize(PrincipalContext principal, Agreement agreement, String action, UUID delegationId,
                            ExecutionContext context) {
        AuthorizationDecision decision = authorizer.require(AuthorizationRequest.of(principal, action,
                        new ResourceReference("agreement", agreement.id().toString(),
                                agreement.counterpartNetworkOf(principal.networkId())))
                .withCapabilityType(agreement.capabilityTypeCode()).withDelegation(delegationId), context);
        return actor(principal, decision);
    }

    private static Actor actor(PrincipalContext principal, AuthorizationDecision decision) {
        return new Actor(principal.principalId(), principal.identityId(), principal.networkId(),
                principal.organizationId(), principal.networkAdministrator(),
                decision == null ? null : decision.delegationId(), decision == null ? null : decision.id(),
                decision == null ? null : decision.federationId());
    }

    private Agreement persist(Agreement agreement, ExecutionContext context) {
        Agreement saved = agreements.save(agreement);
        events.publishAll(agreement.pullEvents(), context.withNetwork(agreement.networkId()));
        return saved;
    }
}
