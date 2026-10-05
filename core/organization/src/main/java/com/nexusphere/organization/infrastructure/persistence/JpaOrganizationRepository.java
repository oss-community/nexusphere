package com.nexusphere.organization.infrastructure.persistence;

import com.nexusphere.organization.domain.model.Organization;
import com.nexusphere.organization.domain.repository.OrganizationRepository;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
class JpaOrganizationRepository implements OrganizationRepository {

    private final OrganizationJpaRepository jpa;

    JpaOrganizationRepository(OrganizationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Organization save(Organization organization) {
        try {
            return toDomain(jpa.saveAndFlush(toEntity(organization)));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("ORGANIZATION_NAME_TAKEN", "An organization named '" + organization.name()
                    + "' already exists in network " + organization.networkId());
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    "Organization " + organization.id() + " was changed concurrently");
        }
    }

    @Override
    public Optional<Organization> findById(NetworkId networkId, OrganizationId id) {
        return jpa.findByIdAndNetworkId(id.value(), networkId.value()).map(JpaOrganizationRepository::toDomain);
    }

    @Override
    public List<Organization> findAll(NetworkId networkId) {
        return jpa.findByNetworkIdOrderByCreatedAtAscIdAsc(networkId.value()).stream()
                .map(JpaOrganizationRepository::toDomain).toList();
    }

    @Override
    public boolean existsByName(NetworkId networkId, String name) {
        return jpa.existsByNetworkIdAndNameIgnoreCase(networkId.value(), name);
    }

    private static OrganizationEntity toEntity(Organization organization) {
        return new OrganizationEntity(organization.id().value(), organization.networkId().value(), organization.name(),
                organization.status(), organization.createdAt(), organization.updatedAt(), organization.version());
    }

    private static Organization toDomain(OrganizationEntity entity) {
        return Organization.restore(new OrganizationId(entity.getId()), new NetworkId(entity.getNetworkId()),
                entity.getName(), entity.getStatus(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getVersion());
    }
}
