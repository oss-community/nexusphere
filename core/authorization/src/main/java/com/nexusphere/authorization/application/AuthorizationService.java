package com.nexusphere.authorization.application;

import com.nexusphere.authorization.contract.AuthorizationDecision;
import com.nexusphere.authorization.contract.AuthorizationRequest;
import com.nexusphere.authorization.contract.Authorizer;
import com.nexusphere.authorization.contract.FederationContext;
import com.nexusphere.authorization.contract.FederationContextPort;
import com.nexusphere.authorization.contract.ResourceOwner;
import com.nexusphere.authorization.contract.TrustEvidencePort;
import com.nexusphere.authorization.domain.model.AuthorizationPolicy;
import com.nexusphere.authorization.domain.model.Role;
import com.nexusphere.authorization.domain.model.RoleAssignment;
import com.nexusphere.authorization.domain.repository.AuthorizationDecisionRepository;
import com.nexusphere.authorization.domain.repository.RoleAssignmentRepository;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.network.contract.NetworkDirectory;
import com.nexusphere.network.contract.NetworkSnapshot;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AuthorizationService implements Authorizer {

    private final RoleAssignmentRepository assignments;
    private final AuthorizationDecisionRepository decisions;
    private final DecisionLog log;
    private final NetworkDirectory networks;
    private final FederationContextPort federations;
    private final TrustEvidencePort trust;
    private final TimeProvider time;

    AuthorizationService(RoleAssignmentRepository assignments, AuthorizationDecisionRepository decisions,
                         DecisionLog log, NetworkDirectory networks, FederationContextPort federations,
                         TrustEvidencePort trust, TimeProvider time) {
        this.assignments = assignments;
        this.decisions = decisions;
        this.log = log;
        this.networks = networks;
        this.federations = federations;
        this.trust = trust;
        this.time = time;
    }

    @Override
    public AuthorizationDecision authorize(AuthorizationRequest request, ExecutionContext context) {
        PrincipalContext principal = request.principal();
        NetworkId home = principal.networkId();
        NetworkId target = request.targetNetworkId();
        FederationContext federation = null;
        UUID trustRelationship = null;
        boolean targetActive = true;
        if (!home.equals(target)) {
            targetActive = networks.find(target).map(NetworkSnapshot::active).orElse(false);
            federation = federations.findActive(home, target).orElse(null);
            trustRelationship = trust.findEffective(target, home, request.action()).orElse(null);
        }
        AuthorizationPolicy.Outcome outcome = AuthorizationPolicy.evaluate(new AuthorizationPolicy.Facts(
                request.action(), roles(principal), owns(principal, request.owner()), home, target, targetActive,
                federation, trustRelationship));
        AuthorizationDecision decision = new AuthorizationDecision(UUID.randomUUID(), principal.principalId(), home,
                target, request.action(), request.resource() == null ? null : request.resource().resourceType(),
                request.resource() == null ? null : request.resource().resourceId(), outcome.allowed(),
                outcome.reason(), outcome.matchedRole() == null ? null : outcome.matchedRole().name(), null,
                federation == null ? null : federation.federationId(), trustRelationship, time.now());
        return log.record(decision, context);
    }

    @Override
    public AuthorizationDecision require(AuthorizationRequest request, ExecutionContext context) {
        AuthorizationDecision decision = authorize(request, context);
        if (!decision.allowed()) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, decision.reason(),
                    "The principal is not authorized to " + decision.action(),
                    Map.of("decisionId", decision.id().toString()));
        }
        return decision;
    }

    @Override
    public Set<String> actionsHeldBy(PrincipalContext principal) {
        return roles(principal).stream().flatMap(role -> role.actions().stream()).collect(Collectors.toSet());
    }

    public Set<Role> roles(PrincipalContext principal) {
        Set<Role> roles = EnumSet.of(Role.MEMBER);
        if (principal.networkAdministrator()) {
            roles.add(Role.NETWORK_ADMINISTRATOR);
        }
        assignments.findActive(principal.networkId(), principal.principalId()).stream()
                .map(RoleAssignment::role).forEach(roles::add);
        return roles;
    }

    public AuthorizationDecision decision(PrincipalContext principal, UUID id) {
        return decisions.findById(id)
                .filter(decision -> decision.networkId().equals(principal.networkId()))
                .filter(decision -> decision.principalId().equals(principal.principalId())
                        || actionsHeldBy(principal).contains("audit:read"))
                .orElseThrow(() -> new NotFoundException("AuthorizationDecision", id));
    }

    public Instant now() {
        return time.now();
    }

    private static boolean owns(PrincipalContext principal, ResourceOwner owner) {
        if (owner == null) {
            return false;
        }
        boolean identity = owner.identityId() != null && owner.identityId().equals(principal.identityId());
        boolean organization = owner.organizationId() != null && owner.organizationId().equals(principal.organizationId());
        return identity || organization;
    }
}
