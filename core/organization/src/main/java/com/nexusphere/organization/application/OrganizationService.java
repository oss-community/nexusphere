package com.nexusphere.organization.application;

import com.nexusphere.network.contract.NetworkDirectory;
import com.nexusphere.network.contract.NetworkSnapshot;
import com.nexusphere.organization.contract.OrganizationDirectory;
import com.nexusphere.organization.contract.OrganizationSnapshot;
import com.nexusphere.organization.domain.model.Organization;
import com.nexusphere.organization.domain.repository.OrganizationRepository;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class OrganizationService implements OrganizationDirectory {

    private final OrganizationRepository organizations;
    private final NetworkDirectory networks;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    OrganizationService(OrganizationRepository organizations, NetworkDirectory networks,
                        DomainEventPublisher events, TimeProvider time) {
        this.organizations = organizations;
        this.networks = networks;
        this.events = events;
        this.time = time;
    }

    public Organization register(NetworkId networkId, String name, ExecutionContext context) {
        requireActiveNetwork(networkId);
        String normalized = Organization.normalizeName(name);
        requireUniqueName(networkId, normalized);
        Organization organization = Organization.register(OrganizationId.newId(), networkId, normalized, time.now());
        return persist(organization, context);
    }

    public Organization rename(NetworkId networkId, OrganizationId id, String name, ExecutionContext context) {
        requireActiveNetwork(networkId);
        Organization organization = get(networkId, id);
        String normalized = Organization.normalizeName(name);
        if (!normalized.equalsIgnoreCase(organization.name())) {
            requireUniqueName(networkId, normalized);
        }
        organization.rename(normalized, time.now());
        return persist(organization, context);
    }

    public Organization deactivate(NetworkId networkId, OrganizationId id, ExecutionContext context) {
        requireActiveNetwork(networkId);
        Organization organization = get(networkId, id);
        organization.deactivate(time.now());
        return persist(organization, context);
    }

    @Transactional(readOnly = true)
    public Organization get(NetworkId networkId, OrganizationId id) {
        requireNetwork(networkId);
        return organizations.findById(networkId, id).orElseThrow(() -> new NotFoundException("Organization", id));
    }

    @Transactional(readOnly = true)
    public List<Organization> list(NetworkId networkId) {
        requireNetwork(networkId);
        return organizations.findAll(networkId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrganizationSnapshot> find(NetworkId networkId, OrganizationId id) {
        return organizations.findById(networkId, id).map(organization -> new OrganizationSnapshot(
                organization.id(), organization.networkId(), organization.name(), organization.isActive()));
    }

    private NetworkSnapshot requireNetwork(NetworkId networkId) {
        return networks.find(networkId).orElseThrow(() -> new NotFoundException("Network", networkId));
    }

    private void requireActiveNetwork(NetworkId networkId) {
        if (!requireNetwork(networkId).active()) {
            throw new ConflictException("NETWORK_NOT_ACTIVE", "Network " + networkId + " is not active");
        }
    }

    private void requireUniqueName(NetworkId networkId, String name) {
        if (organizations.existsByName(networkId, name)) {
            throw new ConflictException("ORGANIZATION_NAME_TAKEN",
                    "An organization named '" + name + "' already exists in network " + networkId);
        }
    }

    private Organization persist(Organization organization, ExecutionContext context) {
        Organization saved = organizations.save(organization);
        events.publishAll(organization.pullEvents(), context.withNetwork(organization.networkId()));
        return saved;
    }
}
