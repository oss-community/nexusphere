package com.nexusphere.audit.application;

import com.nexusphere.agreement.contract.AgreementDirectory;
import com.nexusphere.agreement.contract.AgreementSnapshot;
import com.nexusphere.audit.contract.AuditRecord;
import com.nexusphere.audit.contract.AuditTrail;
import com.nexusphere.audit.domain.model.AuditQuery;
import com.nexusphere.audit.domain.model.TrailLink;
import com.nexusphere.audit.domain.repository.AuditEventRepository;
import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.authorization.contract.AuthorizationRequest;
import com.nexusphere.authorization.contract.Authorizer;
import com.nexusphere.authorization.contract.DelegationEvidence;
import com.nexusphere.authorization.contract.DelegationEvidencePort;
import com.nexusphere.capability.contract.CapabilitySnapshot;
import com.nexusphere.capability.contract.CapabilityDirectory;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.membership.contract.PrincipalResolver;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.reference.ResourceReference;
import com.nexusphere.transaction.contract.TransactionDirectory;
import com.nexusphere.transaction.contract.TransactionSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AuditService implements AuditTrail {

    public record Trail(UUID transactionId, List<TrailLink> chain, List<AuditRecord> events) {
    }

    private final AuditEventRepository events;
    private final Authorizer authorizer;
    private final TransactionDirectory transactions;
    private final AgreementDirectory agreements;
    private final CapabilityDirectory capabilities;
    private final DelegationEvidencePort delegations;
    private final PrincipalResolver principals;

    AuditService(AuditEventRepository events, Authorizer authorizer, TransactionDirectory transactions,
                 AgreementDirectory agreements, CapabilityDirectory capabilities, DelegationEvidencePort delegations,
                 PrincipalResolver principals) {
        this.events = events;
        this.authorizer = authorizer;
        this.transactions = transactions;
        this.agreements = agreements;
        this.capabilities = capabilities;
        this.delegations = delegations;
        this.principals = principals;
    }

    public List<AuditRecord> search(PrincipalContext principal, AuditQuery query, ExecutionContext context) {
        requireAuditor(principal, context);
        return events.search(principal.networkId(), query);
    }

    public AuditRecord get(PrincipalContext principal, UUID id, ExecutionContext context) {
        requireAuditor(principal, context);
        return events.findById(principal.networkId(), id).orElseThrow(() -> new NotFoundException("AuditEvent", id));
    }

    public Trail trail(PrincipalContext principal, UUID transactionId, ExecutionContext context) {
        requireAuditor(principal, context);
        TransactionSnapshot transaction = transactions.find(transactionId)
                .filter(found -> found.requesterNetworkId().equals(principal.networkId())
                        || found.providerNetworkId().equals(principal.networkId()))
                .orElseThrow(() -> new NotFoundException("Transaction", transactionId));
        List<TrailLink> chain = new ArrayList<>();
        if (transaction.delegationId() != null) {
            delegations.find(transaction.delegationId()).ifPresent(delegation -> {
                chain.add(delegator(delegation));
                chain.add(new TrailLink("DELEGATION", delegation.id().toString(), attributes(
                        "actions", String.join(" ", delegation.actions().stream().sorted().toList()),
                        "status", delegation.status(), "validFrom", text(delegation.validFrom()),
                        "validUntil", text(delegation.validUntil()))));
            });
        }
        chain.add(new TrailLink("ACTOR", transaction.initiatingPrincipalId().toString(), attributes(
                "identityId", text(transaction.initiatingIdentityId()),
                "networkId", text(transaction.requesterNetworkId()),
                "onBehalfOf", text(transaction.requesterOrganizationId()))));
        if (transaction.federationId() != null) {
            chain.add(new TrailLink("FEDERATION", transaction.federationId().toString(), attributes(
                    "networks", transaction.requesterNetworkId() + " " + transaction.providerNetworkId())));
        }
        CapabilitySnapshot capability = capabilities.find(transaction.capabilityNetworkId(),
                transaction.capabilityId()).orElse(null);
        chain.add(new TrailLink("CAPABILITY", transaction.capabilityId().toString(), capability == null
                ? attributes("networkId", text(transaction.capabilityNetworkId()))
                : attributes("networkId", text(capability.networkId()), "typeCode", capability.typeCode(),
                "ownerType", capability.ownerType(), "ownerId", text(capability.ownerId()),
                "accountableOrganizationId", text(capability.accountableOrganizationId()))));
        AgreementSnapshot agreement = agreements.find(transaction.agreementId()).orElse(null);
        chain.add(new TrailLink("AGREEMENT", transaction.agreementId().toString(), attributes(
                "version", Integer.toString(transaction.agreementVersion()),
                "status", agreement == null ? null : agreement.status(),
                "counterpartyOrganizationId", text(transaction.providerOrganizationId()))));
        chain.add(new TrailLink("TRANSACTION", transaction.id().toString(), attributes(
                "status", transaction.status(), "reason", transaction.reason(),
                "decisionId", text(transaction.decisionId()),
                "executorIdentityId", text(transaction.executorIdentityId()))));
        return new Trail(transaction.id(), chain, related(principal.networkId(), transaction));
    }

    @Override
    public List<AuditRecord> findByTransaction(NetworkId networkId, UUID transactionId) {
        return events.search(networkId, new AuditQuery(transactionId, null, null, null, null, null, null, null,
                null));
    }

    private List<AuditRecord> related(NetworkId networkId, TransactionSnapshot transaction) {
        List<AuditRecord> all = events.search(networkId, AuditQuery.none());
        List<AuditRecord> direct = all.stream()
                .filter(record -> transaction.id().equals(record.transactionId())
                        || transaction.agreementId().equals(record.agreementId()))
                .toList();
        Set<String> correlations = direct.stream().map(AuditRecord::correlationId).collect(Collectors.toSet());
        Set<UUID> decisions = direct.stream().map(AuditRecord::decisionId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return all.stream().filter(record -> direct.contains(record) || correlations.contains(record.correlationId())
                        || decisions.contains(record.decisionId()))
                .toList();
    }

    private TrailLink delegator(DelegationEvidence delegation) {
        Map<String, String> attributes = new LinkedHashMap<>(attributes("networkId", text(delegation.networkId())));
        principals.find(delegation.networkId(), delegation.delegatorPrincipalId()).ifPresent(context -> {
            attributes.put("identityId", context.identityId().toString());
            if (context.organizationId() != null) {
                attributes.put("organizationId", context.organizationId().toString());
            }
        });
        return new TrailLink("DELEGATOR", delegation.delegatorPrincipalId().toString(), attributes);
    }

    private void requireAuditor(PrincipalContext principal, ExecutionContext context) {
        authorizer.require(AuthorizationRequest.of(principal, Actions.AUDIT_READ,
                new ResourceReference("audit", principal.networkId().toString(), principal.networkId())), context);
    }

    private static Map<String, String> attributes(String... pairs) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                attributes.put(pairs[i], pairs[i + 1]);
            }
        }
        return attributes;
    }

    private static String text(Object value) {
        return Objects.toString(value, null);
    }
}
