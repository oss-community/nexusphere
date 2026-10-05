package com.nexusphere.trust.application;

import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.authorization.contract.AuthorizationRequest;
import com.nexusphere.authorization.contract.Authorizer;
import com.nexusphere.identity.contract.IdentityDirectory;
import com.nexusphere.identity.contract.IdentitySnapshot;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.membership.contract.PrincipalResolver;
import com.nexusphere.network.contract.NetworkDirectory;
import com.nexusphere.organization.contract.OrganizationDirectory;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.Identifier;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.reference.ResourceReference;
import com.nexusphere.shared.time.TimeProvider;
import com.nexusphere.trust.contract.PartyReference;
import com.nexusphere.trust.contract.TrustDirectory;
import com.nexusphere.trust.contract.TrustSnapshot;
import com.nexusphere.trust.domain.model.Party;
import com.nexusphere.trust.domain.model.PartyType;
import com.nexusphere.trust.domain.model.TrustLevel;
import com.nexusphere.trust.domain.model.TrustRelationship;
import com.nexusphere.trust.domain.repository.TrustRelationshipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class TrustService implements TrustDirectory {

    public record PartyInput(String type, String id, String networkId) {
    }

    public record Establishment(PartyInput source, PartyInput target, List<String> scopes, String level,
                                Instant effectiveFrom, Instant effectiveUntil) {
    }

    public record Evaluation(boolean trusted, UUID trustRelationshipId) {
    }

    public enum Direction {
        OUTGOING,
        INCOMING
    }

    private final TrustRelationshipRepository relationships;
    private final Authorizer authorizer;
    private final NetworkDirectory networks;
    private final OrganizationDirectory organizations;
    private final IdentityDirectory identities;
    private final PrincipalResolver principals;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    TrustService(TrustRelationshipRepository relationships, Authorizer authorizer, NetworkDirectory networks,
                 OrganizationDirectory organizations, IdentityDirectory identities, PrincipalResolver principals,
                 DomainEventPublisher events, TimeProvider time) {
        this.relationships = relationships;
        this.authorizer = authorizer;
        this.networks = networks;
        this.organizations = organizations;
        this.identities = identities;
        this.principals = principals;
        this.events = events;
        this.time = time;
    }

    public TrustRelationship establish(PrincipalContext principal, Establishment establishment,
                                       ExecutionContext context) {
        requireActiveNetwork(principal.networkId());
        Party source = establishment.source() == null ? Party.network(principal.networkId())
                : party(principal, establishment.source());
        requireManagedSource(principal, source, context);
        if (establishment.target() == null) {
            throw new ValidationException("TRUST_TARGET_REQUIRED", "The trusted party must be specified");
        }
        Party target = party(principal, establishment.target());
        Instant now = time.now();
        TrustRelationship trust = TrustRelationship.establish(UUID.randomUUID(), source, target,
                establishment.scopes(), TrustLevel.parse(establishment.level()), establishment.effectiveFrom(),
                establishment.effectiveUntil(), now);
        if (relationships.findActive(source, target).stream().anyMatch(existing -> existing.isCurrent(now))) {
            throw new ConflictException("TRUST_ALREADY_ESTABLISHED",
                    "An active trust relationship from this source to this target already exists");
        }
        return persist(trust, context);
    }

    public TrustRelationship revoke(PrincipalContext principal, UUID id, ExecutionContext context) {
        TrustRelationship trust = get(principal, id);
        if (!trust.source().networkId().equals(principal.networkId())) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "TRUST_SOURCE_NOT_MANAGED",
                    "Only the trusting side can revoke a trust relationship");
        }
        requireManagedSource(principal, trust.source(), context);
        trust.revoke(time.now());
        return persist(trust, context);
    }

    @Transactional(readOnly = true)
    public TrustRelationship get(PrincipalContext principal, UUID id) {
        return relationships.findById(id).filter(trust -> trust.involves(principal.networkId()))
                .orElseThrow(() -> new NotFoundException("TrustRelationship", id));
    }

    @Transactional(readOnly = true)
    public List<TrustRelationship> list(PrincipalContext principal, Direction direction) {
        return relationships.findInvolving(principal.networkId()).stream()
                .filter(trust -> direction != Direction.OUTGOING || trust.source().networkId().equals(principal.networkId()))
                .filter(trust -> direction != Direction.INCOMING || trust.target().networkId().equals(principal.networkId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Evaluation evaluate(PrincipalContext principal, PartyInput source, PartyInput target, String scope) {
        Party from = reference(principal, source);
        Party to = reference(principal, target);
        if (!from.networkId().equals(principal.networkId()) && !to.networkId().equals(principal.networkId())) {
            return new Evaluation(false, null);
        }
        Instant now = time.now();
        return relationships.findActive(from, to).stream().filter(trust -> trust.covers(scope, now)).findFirst()
                .map(trust -> new Evaluation(true, trust.id())).orElse(new Evaluation(false, null));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TrustSnapshot> findEffective(PartyReference source, PartyReference target, String scope) {
        Instant now = time.now();
        Party from = new Party(PartyType.parse(source.type()), source.id(), source.networkId());
        Party to = new Party(PartyType.parse(target.type()), target.id(), target.networkId());
        return relationships.findActive(from, to).stream().filter(trust -> trust.covers(scope, now)).findFirst()
                .map(trust -> new TrustSnapshot(trust.id(), trust.source().reference(), trust.target().reference(),
                        trust.scopes(), trust.level().name(), trust.effectiveFrom(),
                        trust.effectiveUntil().orElse(null)));
    }

    private Party party(PrincipalContext principal, PartyInput input) {
        Party party = reference(principal, input);
        if (networks.find(party.networkId()).isEmpty()) {
            throw new NotFoundException("Network", party.networkId());
        }
        if (!party.networkId().equals(principal.networkId()) || party.type() == PartyType.NETWORK) {
            return party;
        }
        if (party.type() == PartyType.ORGANIZATION) {
            organizations.find(party.networkId(), new OrganizationId(party.id()))
                    .orElseThrow(() -> new NotFoundException("Organization", party.id()));
            return party;
        }
        IdentitySnapshot identity = identities.find(new IdentityId(party.id()))
                .orElseThrow(() -> new NotFoundException("Identity", party.id()));
        if (!identity.type().equals(party.type().name())) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "TRUST_PARTY_TYPE_MISMATCH",
                    "Identity " + party.id() + " is of type " + identity.type() + ", not " + party.type());
        }
        try {
            principals.resolve(identity.id(), party.networkId());
        } catch (DomainException e) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "TRUST_PARTY_NOT_MEMBER",
                    "Identity " + party.id() + " has no active membership in network " + party.networkId());
        }
        return party;
    }

    private static Party reference(PrincipalContext principal, PartyInput input) {
        if (input == null) {
            return Party.network(principal.networkId());
        }
        PartyType type = PartyType.parse(input.type());
        UUID id = Identifier.parse(input.id(), "Party id");
        if (type == PartyType.NETWORK) {
            return Party.network(new NetworkId(id));
        }
        NetworkId networkId = input.networkId() == null ? principal.networkId() : NetworkId.of(input.networkId());
        return new Party(type, id, networkId);
    }

    private void requireManagedSource(PrincipalContext principal, Party source, ExecutionContext context) {
        if (!source.networkId().equals(principal.networkId())) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "TRUST_SOURCE_OUTSIDE_NETWORK",
                    "Trust can only be established from a party of the current network");
        }
        switch (source.type()) {
            case NETWORK -> authorizer.require(AuthorizationRequest.of(principal, Actions.TRUST_MANAGE,
                    new ResourceReference("network", source.id().toString(), principal.networkId())), context);
            case ORGANIZATION -> {
                boolean own = principal.organizationId() != null
                        && principal.organizationId().value().equals(source.id());
                if (!own && !authorizer.actionsHeldBy(principal).contains(Actions.TRUST_MANAGE)) {
                    throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "TRUST_SOURCE_NOT_MANAGED",
                            "Only members of the organization can manage its trust");
                }
            }
            default -> throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION,
                    "TRUST_SOURCE_TYPE_UNSUPPORTED", "Trust can be established by a network or an organization");
        }
    }

    private void requireActiveNetwork(NetworkId networkId) {
        boolean active = networks.find(networkId).orElseThrow(() -> new NotFoundException("Network", networkId)).active();
        if (!active) {
            throw new ConflictException("NETWORK_NOT_ACTIVE", "Network " + networkId + " is not active");
        }
    }

    private TrustRelationship persist(TrustRelationship trust, ExecutionContext context) {
        TrustRelationship saved = relationships.save(trust);
        events.publishAll(trust.pullEvents(), context.withNetwork(trust.source().networkId()));
        return saved;
    }
}
