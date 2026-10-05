package com.nexusphere.network.infrastructure.persistence;

import com.nexusphere.network.domain.model.Network;
import com.nexusphere.network.domain.repository.NetworkRepository;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.NetworkId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
class JpaNetworkRepository implements NetworkRepository {

    private final NetworkJpaRepository jpa;

    JpaNetworkRepository(NetworkJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Network save(Network network) {
        try {
            return toDomain(jpa.saveAndFlush(toEntity(network)));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("NETWORK_NAME_TAKEN", "A network named '" + network.name() + "' already exists");
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("CONCURRENT_MODIFICATION", "Network " + network.id() + " was changed concurrently");
        }
    }

    @Override
    public Optional<Network> findById(NetworkId id) {
        return jpa.findById(id.value()).map(JpaNetworkRepository::toDomain);
    }

    @Override
    public List<Network> findAll() {
        return jpa.findAll(Sort.by("createdAt", "id")).stream().map(JpaNetworkRepository::toDomain).toList();
    }

    @Override
    public boolean existsByName(String name) {
        return jpa.existsByNameIgnoreCase(name);
    }

    private static NetworkEntity toEntity(Network network) {
        return new NetworkEntity(network.id().value(), network.name(), network.description(), network.status(),
                network.createdAt(), network.updatedAt(), network.version());
    }

    private static Network toDomain(NetworkEntity entity) {
        return Network.restore(new NetworkId(entity.getId()), entity.getName(), entity.getDescription(),
                entity.getStatus(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getVersion());
    }
}
