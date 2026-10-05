package com.nexusphere.capability.infrastructure.persistence;

import com.nexusphere.capability.domain.model.Capability;
import com.nexusphere.capability.domain.model.CapabilityOwner;
import com.nexusphere.capability.domain.model.CapabilityStatus;
import com.nexusphere.capability.domain.repository.CapabilityRepository;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
class JpaCapabilityRepository implements CapabilityRepository {

    private final CapabilityJpaRepository jpa;
    private final JsonDocuments json;

    JpaCapabilityRepository(CapabilityJpaRepository jpa, JsonDocuments json) {
        this.jpa = jpa;
        this.json = json;
    }

    @Override
    public Capability save(Capability capability) {
        try {
            return toDomain(jpa.saveAndFlush(toEntity(capability)));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("CAPABILITY_NAME_TAKEN",
                    "The owner already has a capability named " + capability.name());
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("CONCURRENT_MODIFICATION", "Capability " + capability.id() + " was changed concurrently");
        }
    }

    @Override
    public Optional<Capability> findById(NetworkId networkId, CapabilityId id) {
        return jpa.findByIdAndNetworkId(id.value(), networkId.value()).map(this::toDomain);
    }

    @Override
    public List<Capability> findAll(NetworkId networkId) {
        return jpa.findByNetworkIdOrderByCreatedAtAscIdAsc(networkId.value()).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean existsCurrentName(NetworkId networkId, CapabilityOwner owner, String name) {
        return jpa.existsCurrentName(networkId.value(), owner.type(), owner.id(), name, CapabilityStatus.WITHDRAWN);
    }

    private CapabilityEntity toEntity(Capability capability) {
        return new CapabilityEntity(capability.id().value(), capability.networkId().value(), capability.owner().type(),
                capability.owner().id(), capability.accountableOrganizationId().map(OrganizationId::value).orElse(null),
                capability.name(), capability.description(), capability.typeId(), capability.typeCode(),
                capability.typeVersion(), json.write(capability.specification()), capability.visibility(),
                capability.status(), capability.createdAt(), capability.publishedAt().orElse(null),
                capability.withdrawnAt().orElse(null), capability.version());
    }

    private Capability toDomain(CapabilityEntity entity) {
        return Capability.restore(new CapabilityId(entity.getId()), new NetworkId(entity.getNetworkId()),
                new CapabilityOwner(entity.getOwnerType(), entity.getOwnerId()),
                entity.getAccountableOrganizationId() == null ? null
                        : new OrganizationId(entity.getAccountableOrganizationId()),
                entity.getName(), entity.getDescription(), entity.getTypeId(), entity.getTypeCode(),
                entity.getTypeVersion(), json.read(entity.getSpecification()), entity.getVisibility(),
                entity.getStatus(), entity.getCreatedAt(), entity.getPublishedAt(), entity.getWithdrawnAt(),
                entity.getVersion());
    }
}
