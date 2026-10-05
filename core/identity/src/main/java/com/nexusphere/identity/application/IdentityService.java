package com.nexusphere.identity.application;

import com.nexusphere.identity.contract.IdentityDirectory;
import com.nexusphere.identity.contract.IdentitySnapshot;
import com.nexusphere.identity.domain.model.Identity;
import com.nexusphere.identity.domain.model.IdentityType;
import com.nexusphere.identity.domain.model.Ownership;
import com.nexusphere.identity.domain.repository.IdentityRepository;
import com.nexusphere.network.contract.NetworkDirectory;
import com.nexusphere.organization.contract.OrganizationDirectory;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

@Service
@Transactional
public class IdentityService implements IdentityDirectory {

    public record CreateIdentity(IdentityType type, String displayName, Ownership ownership,
                                 String agentProvider, String agentModel) {
    }

    private final IdentityRepository identities;
    private final NetworkDirectory networks;
    private final OrganizationDirectory organizations;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    IdentityService(IdentityRepository identities, NetworkDirectory networks, OrganizationDirectory organizations,
                    DomainEventPublisher events, TimeProvider time) {
        this.identities = identities;
        this.networks = networks;
        this.organizations = organizations;
        this.events = events;
        this.time = time;
    }

    public Identity create(CreateIdentity command, ExecutionContext context) {
        if (command.ownership() != null) {
            requireActiveOwner(command.ownership());
        }
        Identity identity = Identity.create(IdentityId.newId(), command.type(), command.displayName(),
                command.ownership(), command.agentProvider(), command.agentModel(), time.now());
        return persist(identity, context);
    }

    public Identity suspend(IdentityId id, ExecutionContext context) {
        return change(id, context, Identity::suspend);
    }

    public Identity activate(IdentityId id, ExecutionContext context) {
        return change(id, context, Identity::activate);
    }

    @Transactional(readOnly = true)
    public Identity get(IdentityId id) {
        return identities.findById(id).orElseThrow(() -> new NotFoundException("Identity", id));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdentitySnapshot> find(IdentityId id) {
        return identities.findById(id).map(IdentityService::snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdentitySnapshot> findAll(Collection<IdentityId> ids) {
        return identities.findAll(ids).stream().map(IdentityService::snapshot).toList();
    }

    private void requireActiveOwner(Ownership ownership) {
        boolean networkActive = networks.find(ownership.networkId())
                .orElseThrow(() -> new NotFoundException("Network", ownership.networkId()))
                .active();
        if (!networkActive) {
            throw new ConflictException("NETWORK_NOT_ACTIVE", "Network " + ownership.networkId() + " is not active");
        }
        boolean organizationActive = organizations.find(ownership.networkId(), ownership.organizationId())
                .orElseThrow(() -> new NotFoundException("Organization", ownership.organizationId()))
                .active();
        if (!organizationActive) {
            throw new ConflictException("ORGANIZATION_NOT_ACTIVE",
                    "Organization " + ownership.organizationId() + " is not active");
        }
    }

    private Identity change(IdentityId id, ExecutionContext context, BiConsumer<Identity, Instant> transition) {
        Identity identity = get(id);
        transition.accept(identity, time.now());
        return persist(identity, context);
    }

    private Identity persist(Identity identity, ExecutionContext context) {
        Identity saved = identities.save(identity);
        events.publishAll(identity.pullEvents(), context);
        return saved;
    }

    static IdentitySnapshot snapshot(Identity identity) {
        return new IdentitySnapshot(identity.id(), identity.type().name(), identity.displayName(), identity.isActive(),
                identity.ownership().map(Ownership::networkId).orElse(null),
                identity.ownership().map(Ownership::organizationId).orElse(null));
    }
}
