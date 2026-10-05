package com.nexusphere.identity.infrastructure.persistence;

import com.nexusphere.identity.domain.model.Identity;
import com.nexusphere.identity.domain.model.Ownership;
import com.nexusphere.identity.domain.repository.IdentityRepository;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
class JpaIdentityRepository implements IdentityRepository {

    private final IdentityJpaRepository jpa;

    JpaIdentityRepository(IdentityJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Identity save(Identity identity) {
        try {
            return toDomain(jpa.saveAndFlush(toEntity(identity)));
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("CONCURRENT_MODIFICATION", "Identity " + identity.id() + " was changed concurrently");
        }
    }

    @Override
    public Optional<Identity> findById(IdentityId id) {
        return jpa.findById(id.value()).map(JpaIdentityRepository::toDomain);
    }

    @Override
    public List<Identity> findAll(Collection<IdentityId> ids) {
        return jpa.findAllById(ids.stream().map(IdentityId::value).toList()).stream()
                .map(JpaIdentityRepository::toDomain).toList();
    }

    private static IdentityEntity toEntity(Identity identity) {
        Ownership ownership = identity.ownership().orElse(null);
        return new IdentityEntity(identity.id().value(), identity.type(), identity.displayName(),
                ownership == null ? null : ownership.networkId().value(),
                ownership == null ? null : ownership.organizationId().value(),
                identity.agentProvider(), identity.agentModel(), identity.status(), identity.createdAt(),
                identity.updatedAt(), identity.version());
    }

    private static Identity toDomain(IdentityEntity entity) {
        Ownership ownership = entity.getOwningOrganizationId() == null ? null : new Ownership(
                new NetworkId(entity.getOwningNetworkId()), new OrganizationId(entity.getOwningOrganizationId()));
        return Identity.restore(new IdentityId(entity.getId()), entity.getType(), entity.getDisplayName(), ownership,
                entity.getAgentProvider(), entity.getAgentModel(), entity.getStatus(), entity.getCreatedAt(),
                entity.getUpdatedAt(), entity.getVersion());
    }
}
