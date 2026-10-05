package com.nexusphere.federation.application;

import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.authorization.contract.AuthorizationRequest;
import com.nexusphere.authorization.contract.Authorizer;
import com.nexusphere.federation.contract.FederationDirectory;
import com.nexusphere.federation.contract.FederationSnapshot;
import com.nexusphere.federation.domain.model.Federation;
import com.nexusphere.federation.domain.model.FederationScope;
import com.nexusphere.federation.domain.model.FederationStatus;
import com.nexusphere.federation.domain.repository.FederationRepository;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.network.contract.NetworkDirectory;
import com.nexusphere.network.contract.NetworkSnapshot;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.reference.ResourceReference;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Service
@Transactional
public class FederationService implements FederationDirectory {

    public record Proposal(String partnerNetworkId, List<String> scopes, Instant effectiveUntil) {
    }

    public enum Action {
        SUBMIT,
        ACCEPT,
        REJECT,
        SUSPEND,
        RESUME,
        TERMINATE
    }

    private final FederationRepository federations;
    private final Authorizer authorizer;
    private final NetworkDirectory networks;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    FederationService(FederationRepository federations, Authorizer authorizer, NetworkDirectory networks,
                      DomainEventPublisher events, TimeProvider time) {
        this.federations = federations;
        this.authorizer = authorizer;
        this.networks = networks;
        this.events = events;
        this.time = time;
    }

    public Federation propose(PrincipalContext principal, Proposal proposal, ExecutionContext context) {
        authorizer.require(AuthorizationRequest.of(principal, Actions.FEDERATION_MANAGE,
                new ResourceReference("federation", "new", principal.networkId())), context);
        NetworkId proposer = principal.networkId();
        NetworkId partner = NetworkId.of(proposal.partnerNetworkId());
        requireActive(proposer);
        requireActive(partner);
        List<FederationScope> scopes = proposal.scopes() == null ? List.of()
                : proposal.scopes().stream().map(FederationScope::parse).toList();
        Federation federation = Federation.propose(UUID.randomUUID(), proposer, partner, scopes,
                proposal.effectiveUntil(), time.now());
        if (federations.findBetween(proposer, partner).stream().anyMatch(open -> !open.status().isTerminal())) {
            throw new ConflictException("FEDERATION_ALREADY_EXISTS",
                    "An open federation between networks " + proposer + " and " + partner + " already exists");
        }
        return persist(federation, principal.networkId(), context);
    }

    public Federation apply(PrincipalContext principal, UUID id, Action action, Long expectedVersion,
                            ExecutionContext context) {
        Federation federation = get(principal, id);
        authorizer.require(AuthorizationRequest.of(principal, Actions.FEDERATION_MANAGE,
                new ResourceReference("federation", id.toString(), principal.networkId())), context);
        if (expectedVersion != null && !expectedVersion.equals(federation.version())) {
            throw new ConflictException("FEDERATION_VERSION_MISMATCH", "Federation " + id + " is at version "
                    + federation.version() + ", not " + expectedVersion);
        }
        if (action == Action.ACCEPT || action == Action.RESUME) {
            requireActive(federation.counterpart(principal.networkId()));
        }
        BiConsumer<NetworkId, Instant> transition = switch (action) {
            case SUBMIT -> federation::submit;
            case ACCEPT -> federation::accept;
            case REJECT -> federation::reject;
            case SUSPEND -> federation::suspend;
            case RESUME -> federation::resume;
            case TERMINATE -> federation::terminate;
        };
        transition.accept(principal.networkId(), time.now());
        return persist(federation, principal.networkId(), context);
    }

    @Transactional(readOnly = true)
    public Federation get(PrincipalContext principal, UUID id) {
        return federations.findById(id).filter(federation -> federation.involves(principal.networkId()))
                .orElseThrow(() -> new NotFoundException("Federation", id));
    }

    @Transactional(readOnly = true)
    public List<Federation> list(PrincipalContext principal) {
        return federations.findInvolving(principal.networkId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FederationSnapshot> findActive(NetworkId first, NetworkId second) {
        Instant now = time.now();
        return federations.findBetween(first, second).stream().filter(federation -> federation.isActive(now))
                .findFirst()
                .map(federation -> new FederationSnapshot(federation.id(), federation.proposerNetworkId(),
                        federation.partnerNetworkId(),
                        federation.scopes().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                        FederationStatus.ACTIVE.name(), federation.effectiveUntil().orElse(null)));
    }

    private void requireActive(NetworkId networkId) {
        NetworkSnapshot network = networks.find(networkId).orElseThrow(() -> new NotFoundException("Network", networkId));
        if (!network.active()) {
            throw new ConflictException("NETWORK_NOT_ACTIVE", "Network " + networkId + " is not active");
        }
    }

    private Federation persist(Federation federation, NetworkId actingNetwork, ExecutionContext context) {
        Federation saved = federations.save(federation);
        events.publishAll(federation.pullEvents(), context.withNetwork(actingNetwork));
        return saved;
    }
}
