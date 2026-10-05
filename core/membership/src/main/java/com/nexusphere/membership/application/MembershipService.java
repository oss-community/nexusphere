package com.nexusphere.membership.application;

import com.nexusphere.identity.contract.IdentityDirectory;
import com.nexusphere.identity.contract.IdentitySnapshot;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.membership.contract.PrincipalResolver;
import com.nexusphere.membership.domain.model.Membership;
import com.nexusphere.membership.domain.model.MembershipRole;
import com.nexusphere.membership.domain.repository.MembershipRepository;
import com.nexusphere.network.contract.NetworkDirectory;
import com.nexusphere.organization.contract.OrganizationDirectory;
import com.nexusphere.organization.contract.OrganizationSnapshot;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class MembershipService implements PrincipalResolver {

    public record Member(Membership membership, IdentitySnapshot identity) {
    }

    private final MembershipRepository memberships;
    private final IdentityDirectory identities;
    private final NetworkDirectory networks;
    private final OrganizationDirectory organizations;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    MembershipService(MembershipRepository memberships, IdentityDirectory identities, NetworkDirectory networks,
                      OrganizationDirectory organizations, DomainEventPublisher events, TimeProvider time) {
        this.memberships = memberships;
        this.identities = identities;
        this.networks = networks;
        this.organizations = organizations;
        this.events = events;
        this.time = time;
    }

    public Membership activate(NetworkId networkId, IdentityId identityId, OrganizationId organizationId,
                               MembershipRole role, ExecutionContext context) {
        requireActiveNetwork(networkId);
        IdentitySnapshot identity = identities.find(identityId)
                .orElseThrow(() -> new NotFoundException("Identity", identityId));
        if (!identity.active()) {
            throw new ConflictException("IDENTITY_NOT_ACTIVE", "Identity " + identityId + " is not active");
        }
        OrganizationId organization = organizationFor(identity, networkId, organizationId);
        if (organization != null) {
            OrganizationSnapshot snapshot = organizations.find(networkId, organization)
                    .orElseThrow(() -> new NotFoundException("Organization", organization));
            if (!snapshot.active()) {
                throw new ConflictException("ORGANIZATION_NOT_ACTIVE", "Organization " + organization + " is not active");
            }
        }
        if (memberships.findActive(identityId, networkId).isPresent()) {
            throw new ConflictException("MEMBERSHIP_ALREADY_ACTIVE",
                    "Identity " + identityId + " already has an active membership in network " + networkId);
        }
        Membership membership = Membership.activate(UUID.randomUUID(), identityId, networkId, organization, role,
                time.now());
        return persist(membership, context);
    }

    public Membership terminate(NetworkId networkId, UUID membershipId, ExecutionContext context) {
        requireActiveNetwork(networkId);
        Membership membership = get(networkId, membershipId);
        membership.terminate(time.now());
        return persist(membership, context);
    }

    @Transactional(readOnly = true)
    public Membership get(NetworkId networkId, UUID membershipId) {
        requireNetwork(networkId);
        return memberships.findById(networkId, membershipId)
                .orElseThrow(() -> new NotFoundException("Membership", membershipId));
    }

    @Transactional(readOnly = true)
    public List<Membership> list(NetworkId networkId) {
        requireNetwork(networkId);
        return memberships.findAll(networkId);
    }

    @Transactional(readOnly = true)
    public List<Member> members(PrincipalContext principal) {
        List<Membership> active = memberships.findActive(principal.networkId());
        List<IdentitySnapshot> found = identities.findAll(active.stream().map(Membership::identityId).toList());
        return active.stream()
                .flatMap(membership -> found.stream()
                        .filter(identity -> identity.id().equals(membership.identityId()))
                        .map(identity -> new Member(membership, identity)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Member member(PrincipalContext principal, IdentityId identityId) {
        Membership membership = memberships.findActive(identityId, principal.networkId())
                .orElseThrow(() -> new NotFoundException("Identity", identityId));
        IdentitySnapshot identity = identities.find(identityId)
                .orElseThrow(() -> new NotFoundException("Identity", identityId));
        return new Member(membership, identity);
    }

    @Override
    @Transactional(readOnly = true)
    public PrincipalContext resolve(IdentityId identityId, NetworkId networkId) {
        IdentitySnapshot identity = identities.find(identityId).filter(IdentitySnapshot::active)
                .orElseThrow(() -> new DomainException(ErrorCategory.AUTHENTICATION_ERROR, "IDENTITY_NOT_ACTIVE",
                        "The authenticated identity is not active"));
        Membership membership = memberships.findActive(identityId, networkId)
                .orElseThrow(() -> new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "NETWORK_ACCESS_DENIED",
                        "The authenticated identity has no active membership in network " + networkId));
        return new PrincipalContext(membership.principalId(), identity.id(), identity.type(), networkId,
                membership.organizationId().orElse(null), membership.isAdministrator());
    }

    private OrganizationId organizationFor(IdentitySnapshot identity, NetworkId networkId, OrganizationId requested) {
        if (!identity.ownedByOrganization()) {
            return requested;
        }
        if (!identity.owningNetworkId().equals(networkId)) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "MEMBERSHIP_OUTSIDE_OWNING_NETWORK",
                    "An identity of type " + identity.type() + " can only be a member of its owning network");
        }
        if (requested != null && !requested.equals(identity.owningOrganizationId())) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "MEMBERSHIP_OUTSIDE_OWNING_ORGANIZATION",
                    "An identity of type " + identity.type() + " can only be a member through its owning organization");
        }
        return identity.owningOrganizationId();
    }

    private void requireNetwork(NetworkId networkId) {
        networks.find(networkId).orElseThrow(() -> new NotFoundException("Network", networkId));
    }

    private void requireActiveNetwork(NetworkId networkId) {
        boolean active = networks.find(networkId).orElseThrow(() -> new NotFoundException("Network", networkId)).active();
        if (!active) {
            throw new ConflictException("NETWORK_NOT_ACTIVE", "Network " + networkId + " is not active");
        }
    }

    private Membership persist(Membership membership, ExecutionContext context) {
        Membership saved = memberships.save(membership);
        events.publishAll(membership.pullEvents(), context.withNetwork(membership.networkId()));
        return saved;
    }
}
