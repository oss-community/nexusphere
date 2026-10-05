package com.nexusphere.delegation.application;

import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.authorization.contract.AuthorizationRequest;
import com.nexusphere.authorization.contract.Authorizer;
import com.nexusphere.delegation.domain.model.Delegation;
import com.nexusphere.delegation.domain.model.DelegationConstraints;
import com.nexusphere.delegation.domain.repository.DelegationRepository;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.membership.contract.PrincipalResolver;
import com.nexusphere.network.contract.NetworkDirectory;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;
import com.nexusphere.shared.reference.ResourceReference;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@Transactional
public class DelegationService {

    public record Grant(String delegatePrincipalId, List<String> actions, List<String> capabilityTypes,
                        List<String> networks, List<String> resourceTypes, Instant validFrom, Instant validUntil) {
    }

    public record Filter(PrincipalId delegatePrincipalId, PrincipalId delegatorPrincipalId, boolean effectiveOnly) {
    }

    private final DelegationRepository delegations;
    private final Authorizer authorizer;
    private final PrincipalResolver principals;
    private final NetworkDirectory networks;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    DelegationService(DelegationRepository delegations, Authorizer authorizer, PrincipalResolver principals,
                      NetworkDirectory networks, DomainEventPublisher events, TimeProvider time) {
        this.delegations = delegations;
        this.authorizer = authorizer;
        this.principals = principals;
        this.networks = networks;
        this.events = events;
        this.time = time;
    }

    public Delegation grant(PrincipalContext principal, Grant grant, ExecutionContext context) {
        requireActiveNetwork(principal.networkId());
        if (grant.delegatePrincipalId() == null) {
            throw new ValidationException("DELEGATE_REQUIRED", "The delegate principal must be specified");
        }
        PrincipalId delegate = PrincipalId.of(grant.delegatePrincipalId());
        principals.find(principal.networkId(), delegate).orElseThrow(() -> new NotFoundException("Principal", delegate));
        Instant now = time.now();
        DelegationConstraints constraints = DelegationConstraints.of(grant.capabilityTypes(),
                grant.networks() == null ? null : grant.networks().stream().map(NetworkId::of).toList(),
                grant.resourceTypes());
        Delegation delegation = Delegation.grant(UUID.randomUUID(), principal.networkId(), principal.principalId(),
                delegate, grant.actions(), constraints, grant.validFrom(), grant.validUntil(), now);
        requireAuthorityOver(principal, delegation.actions(), now);
        authorizer.require(AuthorizationRequest.of(principal, Actions.DELEGATION_GRANT,
                new ResourceReference("principal", delegate.toString(), principal.networkId())), context);
        return persist(delegation, context);
    }

    public Delegation revoke(PrincipalContext principal, UUID id, ExecutionContext context) {
        return change(principal, id, context, delegation -> delegation.revoke(time.now()));
    }

    public Delegation suspend(PrincipalContext principal, UUID id, ExecutionContext context) {
        return change(principal, id, context, delegation -> delegation.suspend(time.now()));
    }

    public Delegation resume(PrincipalContext principal, UUID id, ExecutionContext context) {
        return change(principal, id, context, delegation -> delegation.resume(time.now()));
    }

    @Transactional(readOnly = true)
    public Delegation get(PrincipalContext principal, UUID id) {
        return delegations.findById(id).filter(delegation -> delegation.networkId().equals(principal.networkId()))
                .filter(delegation -> delegation.involves(principal.principalId()) || oversees(principal))
                .orElseThrow(() -> new NotFoundException("Delegation", id));
    }

    @Transactional(readOnly = true)
    public List<Delegation> list(PrincipalContext principal, Filter filter) {
        Instant now = time.now();
        boolean oversees = oversees(principal);
        return delegations.findByNetwork(principal.networkId()).stream()
                .filter(delegation -> oversees || delegation.involves(principal.principalId()))
                .filter(delegation -> filter.delegatePrincipalId() == null
                        || delegation.delegatePrincipalId().equals(filter.delegatePrincipalId()))
                .filter(delegation -> filter.delegatorPrincipalId() == null
                        || delegation.delegatorPrincipalId().equals(filter.delegatorPrincipalId()))
                .filter(delegation -> !filter.effectiveOnly() || delegation.isEffective(now))
                .toList();
    }

    private void requireAuthorityOver(PrincipalContext principal, Set<String> actions, Instant now) {
        Set<String> held = authorizer.actionsHeldBy(principal);
        Set<String> missing = actions.stream().filter(action -> !held.contains(action))
                .collect(Collectors.toCollection(TreeSet::new));
        if (missing.isEmpty()) {
            return;
        }
        Set<String> delegatedToPrincipal = delegations.findGrantedTo(principal.networkId(), principal.principalId())
                .stream().filter(delegation -> delegation.isEffective(now))
                .flatMap(delegation -> delegation.actions().stream()).collect(Collectors.toSet());
        if (missing.stream().anyMatch(delegatedToPrincipal::contains)) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "DELEGATION_DEPTH_EXCEEDED",
                    "Authority received through a delegation cannot be delegated again",
                    Map.of("actions", String.join(" ", missing)));
        }
        throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "DELEGATION_EXCEEDS_AUTHORITY",
                "The delegator does not hold every delegated action", Map.of("actions", String.join(" ", missing)));
    }

    private Delegation change(PrincipalContext principal, UUID id, ExecutionContext context,
                              Consumer<Delegation> transition) {
        Delegation delegation = get(principal, id);
        if (!delegation.delegatorPrincipalId().equals(principal.principalId())
                && !authorizer.actionsHeldBy(principal).contains(Actions.ROLE_ASSIGN)) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "DELEGATION_NOT_MANAGED",
                    "Only the delegator or a network administrator can change a delegation");
        }
        transition.accept(delegation);
        return persist(delegation, context);
    }

    private boolean oversees(PrincipalContext principal) {
        return authorizer.actionsHeldBy(principal).contains(Actions.AUDIT_READ);
    }

    private void requireActiveNetwork(NetworkId networkId) {
        boolean active = networks.find(networkId).orElseThrow(() -> new NotFoundException("Network", networkId)).active();
        if (!active) {
            throw new ConflictException("NETWORK_NOT_ACTIVE", "Network " + networkId + " is not active");
        }
    }

    private Delegation persist(Delegation delegation, ExecutionContext context) {
        Delegation saved = delegations.save(delegation);
        events.publishAll(delegation.pullEvents(), context.withNetwork(delegation.networkId()));
        return saved;
    }
}
