package com.nexusphere.trust.infrastructure.persistence;

import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.trust.domain.model.Party;
import com.nexusphere.trust.domain.model.TrustRelationship;
import com.nexusphere.trust.domain.model.TrustStatus;
import com.nexusphere.trust.domain.repository.TrustRelationshipRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
class JpaTrustRelationshipRepository implements TrustRelationshipRepository {

    private final TrustRelationshipJpaRepository jpa;

    JpaTrustRelationshipRepository(TrustRelationshipJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public TrustRelationship save(TrustRelationship trust) {
        try {
            return toDomain(jpa.saveAndFlush(toEntity(trust)));
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("CONCURRENT_MODIFICATION", "Trust relationship " + trust.id() + " was changed concurrently");
        }
    }

    @Override
    public Optional<TrustRelationship> findById(UUID id) {
        return jpa.findById(id).map(JpaTrustRelationshipRepository::toDomain);
    }

    @Override
    public List<TrustRelationship> findInvolving(NetworkId networkId) {
        return jpa.findInvolving(networkId.value()).stream().map(JpaTrustRelationshipRepository::toDomain).toList();
    }

    @Override
    public List<TrustRelationship> findActive(Party source, Party target) {
        return jpa.findBySourceTypeAndSourceIdAndSourceNetworkIdAndTargetTypeAndTargetIdAndTargetNetworkIdAndStatus(
                        source.type(), source.id(), source.networkId().value(), target.type(), target.id(),
                        target.networkId().value(), TrustStatus.ACTIVE).stream()
                .map(JpaTrustRelationshipRepository::toDomain).toList();
    }

    private static TrustRelationshipEntity toEntity(TrustRelationship trust) {
        return new TrustRelationshipEntity(trust.id(), trust.source().type(), trust.source().id(),
                trust.source().networkId().value(), trust.target().type(), trust.target().id(),
                trust.target().networkId().value(), String.join(" ", trust.scopes()), trust.level(), trust.status(),
                trust.effectiveFrom(), trust.effectiveUntil().orElse(null), trust.createdAt(),
                trust.revokedAt().orElse(null), trust.version());
    }

    private static TrustRelationship toDomain(TrustRelationshipEntity entity) {
        Set<String> scopes = Arrays.stream(entity.getScopes().split(" ")).collect(Collectors.toSet());
        return TrustRelationship.restore(entity.getId(),
                new Party(entity.getSourceType(), entity.getSourceId(), new NetworkId(entity.getSourceNetworkId())),
                new Party(entity.getTargetType(), entity.getTargetId(), new NetworkId(entity.getTargetNetworkId())),
                scopes, entity.getLevel(), entity.getStatus(), entity.getEffectiveFrom(), entity.getEffectiveUntil(),
                entity.getCreatedAt(), entity.getRevokedAt(), entity.getVersion());
    }
}
