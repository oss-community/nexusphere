package com.nexusphere.network.application;

import com.nexusphere.network.contract.NetworkDirectory;
import com.nexusphere.network.contract.NetworkSnapshot;
import com.nexusphere.network.domain.model.Network;
import com.nexusphere.network.domain.repository.NetworkRepository;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

@Service
@Transactional
public class NetworkService implements NetworkDirectory {

    private final NetworkRepository networks;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    NetworkService(NetworkRepository networks, DomainEventPublisher events, TimeProvider time) {
        this.networks = networks;
        this.events = events;
        this.time = time;
    }

    public Network create(String name, String description, ExecutionContext context) {
        Network network = Network.create(NetworkId.newId(), name, description, time.now());
        if (networks.existsByName(network.name())) {
            throw new ConflictException("NETWORK_NAME_TAKEN", "A network named '" + network.name() + "' already exists");
        }
        return persist(network, context);
    }

    public Network activate(NetworkId id, ExecutionContext context) {
        return change(id, context, Network::activate);
    }

    public Network suspend(NetworkId id, ExecutionContext context) {
        return change(id, context, Network::suspend);
    }

    public Network archive(NetworkId id, ExecutionContext context) {
        return change(id, context, Network::archive);
    }

    @Transactional(readOnly = true)
    public Network get(NetworkId id) {
        return networks.findById(id).orElseThrow(() -> new NotFoundException("Network", id));
    }

    @Transactional(readOnly = true)
    public List<Network> list() {
        return networks.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NetworkSnapshot> find(NetworkId id) {
        return networks.findById(id).map(network -> new NetworkSnapshot(network.id(), network.name(), network.isActive()));
    }

    private Network change(NetworkId id, ExecutionContext context, BiConsumer<Network, Instant> transition) {
        Network network = get(id);
        transition.accept(network, time.now());
        return persist(network, context);
    }

    private Network persist(Network network, ExecutionContext context) {
        Network saved = networks.save(network);
        events.publishAll(network.pullEvents(), context.withNetwork(network.id()));
        return saved;
    }
}
