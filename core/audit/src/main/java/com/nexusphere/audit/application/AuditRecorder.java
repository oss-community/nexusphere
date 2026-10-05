package com.nexusphere.audit.application;

import com.nexusphere.agreement.contract.AgreementChanged;
import com.nexusphere.audit.contract.AuditRecord;
import com.nexusphere.audit.domain.model.AuditResult;
import com.nexusphere.audit.domain.model.AuditStreams;
import com.nexusphere.audit.domain.repository.AuditEventRepository;
import com.nexusphere.authorization.contract.AuthorizationDecision;
import com.nexusphere.authorization.contract.AuthorizationDenied;
import com.nexusphere.authorization.contract.AuthorizationGranted;
import com.nexusphere.authorization.contract.RoleAssigned;
import com.nexusphere.authorization.contract.RoleRevoked;
import com.nexusphere.identity.contract.CredentialIssued;
import com.nexusphere.identity.contract.CredentialRevoked;
import com.nexusphere.identity.contract.IdentityActivated;
import com.nexusphere.identity.contract.IdentityCreated;
import com.nexusphere.identity.contract.IdentityDirectory;
import com.nexusphere.identity.contract.IdentitySnapshot;
import com.nexusphere.identity.contract.IdentitySuspended;
import com.nexusphere.membership.contract.NetworkAccessDenied;
import com.nexusphere.membership.contract.PrincipalResolver;
import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.event.EventEnvelope;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;
import com.nexusphere.transaction.contract.TransactionChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
class AuditRecorder {

    private final AuditEventRepository events;
    private final IdentityDirectory identities;
    private final PrincipalResolver principals;

    AuditRecorder(AuditEventRepository events, IdentityDirectory identities, PrincipalResolver principals) {
        this.events = events;
        this.identities = identities;
        this.principals = principals;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(EventEnvelope envelope) {
        records(envelope).forEach(events::append);
    }

    List<AuditRecord> records(EventEnvelope envelope) {
        DomainEvent payload = envelope.payload();
        return switch (payload) {
            case AuthorizationGranted granted -> decision(envelope, granted.decision());
            case AuthorizationDenied denied -> decision(envelope, denied.decision());
            case AgreementChanged changed -> agreement(envelope, changed);
            case TransactionChanged changed -> transaction(envelope, changed);
            case RoleAssigned assigned -> List.of(role(envelope, "role:assign", assigned.network(),
                    assigned.principal(), assigned.role(), assigned.assignmentId(), assigned.actor()));
            case RoleRevoked revoked -> List.of(role(envelope, "role:revoke", revoked.network(),
                    revoked.principal(), revoked.role(), revoked.assignmentId(), revoked.actor()));
            case IdentityCreated created -> identity(envelope, created.identity(), "identity:create", "identity",
                    created.identity().toString(), null, Map.of("type", created.type()));
            case IdentityActivated activated -> identity(envelope, activated.identity(), "identity:activate",
                    "identity", activated.identity().toString(), null, Map.of());
            case IdentitySuspended suspended -> identity(envelope, suspended.identity(), "identity:suspend",
                    "identity", suspended.identity().toString(), null, Map.of());
            case CredentialIssued issued -> identity(envelope, issued.identity(), "credential:issue", "credential",
                    issued.credentialId().toString(), null, Map.of("expiresAt", issued.expiresAt().toString()));
            case CredentialRevoked revoked -> identity(envelope, revoked.identity(), "credential:revoke",
                    "credential", revoked.credentialId().toString(), revoked.reason(), Map.of());
            case NetworkAccessDenied denied -> List.of(record(envelope, denied.homeNetworkId(), "network:access",
                    "network", denied.requestedNetworkId().toString(), AuditResult.DENIED, denied.reason()));
            default -> envelope.networkId() == null ? List.of() : List.of(record(envelope, envelope.networkId(),
                    envelope.eventType(), envelope.eventType().split("\\.")[0], null, AuditResult.SUCCEEDED, null));
        };
    }

    private List<AuditRecord> decision(EventEnvelope envelope, AuthorizationDecision decision) {
        String resourceType = decision.resourceType();
        UUID agreement = "agreement".equals(resourceType) ? uuid(decision.resourceId()) : null;
        UUID transaction = "transaction".equals(resourceType) ? uuid(decision.resourceId()) : null;
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("targetNetworkId", decision.targetNetworkId().toString());
        if (decision.matchedRole() != null) {
            metadata.put("matchedRole", decision.matchedRole());
        }
        return List.of(new AuditRecord(UUID.randomUUID(), envelope.eventId(), envelope.eventType(),
                envelope.timestamp(), decision.networkId(), decision.principalId(), envelope.identityId(), null,
                decision.action(), resourceType, decision.resourceId(), decision.federationId(),
                decision.delegationId(), agreement, transaction, decision.id(),
                (decision.allowed() ? AuditResult.ALLOWED : AuditResult.DENIED).name(), decision.reason(),
                envelope.correlationId().toString(), envelope.causationId(), metadata));
    }

    private List<AuditRecord> agreement(EventEnvelope envelope, AgreementChanged changed) {
        Map<String, String> metadata = Map.of("change", changed.change(), "status", changed.status(),
                "version", Integer.toString(changed.version()));
        return networks(changed.agreementNetworkId(), changed.counterpartyNetworkId()).stream()
                .map(network -> new AuditRecord(UUID.randomUUID(), envelope.eventId(), envelope.eventType(),
                        envelope.timestamp(), network, changed.principalId(), envelope.identityId(),
                        changed.accountableOrganizationId(), "agreement:" + changed.change().toLowerCase(),
                        "agreement", changed.agreementId().toString(), changed.federationId(),
                        changed.delegationId(), changed.agreementId(), null, changed.decisionId(),
                        AuditResult.SUCCEEDED.name(), null, envelope.correlationId().toString(),
                        envelope.causationId(), metadata))
                .toList();
    }

    private List<AuditRecord> transaction(EventEnvelope envelope, TransactionChanged changed) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("change", changed.change());
        metadata.put("status", changed.status());
        metadata.put("capabilityId", changed.capabilityId().toString());
        AuditResult result = switch (changed.status()) {
            case "REJECTED" -> AuditResult.REJECTED;
            case "FAILED" -> AuditResult.FAILED;
            default -> AuditResult.SUCCEEDED;
        };
        return networks(changed.transactionNetworkId(), changed.providerNetworkId()).stream()
                .map(network -> new AuditRecord(UUID.randomUUID(), envelope.eventId(), envelope.eventType(),
                        envelope.timestamp(), network, changed.principalId(), envelope.identityId(),
                        changed.accountableOrganizationId(), "transaction:" + changed.change().toLowerCase(),
                        "transaction", changed.transactionId().toString(), changed.federationId(),
                        changed.delegationId(), changed.agreementId(), changed.transactionId(),
                        changed.decisionId(), result.name(), changed.reason(), envelope.correlationId().toString(),
                        envelope.causationId(), metadata))
                .toList();
    }

    private AuditRecord role(EventEnvelope envelope, String action, NetworkId network, PrincipalId principal,
                             String role, UUID assignmentId, PrincipalId actor) {
        return new AuditRecord(UUID.randomUUID(), envelope.eventId(), envelope.eventType(), envelope.timestamp(),
                network, actor, envelope.identityId(), null, action, "principal", principal.toString(), null, null,
                null, null, null, AuditResult.SUCCEEDED.name(), null, envelope.correlationId().toString(),
                envelope.causationId(), Map.of("role", role, "assignmentId", assignmentId.toString()));
    }

    private List<AuditRecord> identity(EventEnvelope envelope, IdentityId subject, String action,
                                       String resourceType, String resourceId, String reason,
                                       Map<String, String> details) {
        Set<NetworkId> networks = new LinkedHashSet<>();
        networks.add(AuditStreams.PLATFORM);
        identities.find(subject).map(IdentitySnapshot::owningNetworkId).ifPresent(networks::add);
        networks.addAll(principals.memberNetworks(subject));
        Map<String, String> metadata = new LinkedHashMap<>(details);
        metadata.put("subjectIdentityId", subject.toString());
        return networks.stream()
                .map(network -> new AuditRecord(UUID.randomUUID(), envelope.eventId(), envelope.eventType(),
                        envelope.timestamp(), network, envelope.principalId(), envelope.identityId(), null, action,
                        resourceType, resourceId, null, null, null, null, null, AuditResult.SUCCEEDED.name(), reason,
                        envelope.correlationId().toString(), envelope.causationId(), metadata))
                .toList();
    }

    private static AuditRecord record(EventEnvelope envelope, NetworkId network, String action, String resourceType,
                                      String resourceId, AuditResult result, String reason) {
        return new AuditRecord(UUID.randomUUID(), envelope.eventId(), envelope.eventType(), envelope.timestamp(),
                network, envelope.principalId(), envelope.identityId(), null, action, resourceType, resourceId, null,
                null, null, null, null, result.name(), reason, envelope.correlationId().toString(),
                envelope.causationId(), Map.of());
    }

    private static Set<NetworkId> networks(NetworkId first, NetworkId second) {
        Set<NetworkId> networks = new LinkedHashSet<>();
        networks.add(first);
        networks.add(second);
        return networks;
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
