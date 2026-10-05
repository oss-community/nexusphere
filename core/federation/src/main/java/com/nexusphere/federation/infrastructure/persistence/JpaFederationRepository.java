package com.nexusphere.federation.infrastructure.persistence;

import com.nexusphere.federation.domain.model.Federation;
import com.nexusphere.federation.domain.model.FederationScope;
import com.nexusphere.federation.domain.repository.FederationRepository;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.NetworkId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
class JpaFederationRepository implements FederationRepository {

    private final FederationJpaRepository jpa;

    JpaFederationRepository(FederationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Federation save(Federation federation) {
        try {
            return toDomain(jpa.saveAndFlush(toEntity(federation)));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("FEDERATION_ALREADY_EXISTS", "An open federation between networks "
                    + federation.proposerNetworkId() + " and " + federation.partnerNetworkId() + " already exists");
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("FEDERATION_VERSION_MISMATCH", "Federation " + federation.id() + " was changed concurrently");
        }
    }

    @Override
    public Optional<Federation> findById(UUID id) {
        return jpa.findById(id).map(JpaFederationRepository::toDomain);
    }

    @Override
    public List<Federation> findInvolving(NetworkId networkId) {
        return jpa.findInvolving(networkId.value()).stream().map(JpaFederationRepository::toDomain).toList();
    }

    @Override
    public List<Federation> findBetween(NetworkId first, NetworkId second) {
        return jpa.findBetween(first.value(), second.value()).stream().map(JpaFederationRepository::toDomain).toList();
    }

    private static FederationEntity toEntity(Federation federation) {
        return new FederationEntity(federation.id(), federation.proposerNetworkId().value(),
                federation.partnerNetworkId().value(),
                federation.scopes().stream().map(Enum::name).sorted().collect(Collectors.joining(" ")),
                federation.status(), federation.effectiveFrom().orElse(null), federation.effectiveUntil().orElse(null),
                federation.suspendedBy().map(NetworkId::value).orElse(null), federation.createdAt(),
                federation.updatedAt(), federation.version());
    }

    private static Federation toDomain(FederationEntity entity) {
        EnumSet<FederationScope> scopes = Arrays.stream(entity.getScopes().split(" ")).map(FederationScope::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(FederationScope.class)));
        return Federation.restore(entity.getId(), new NetworkId(entity.getProposerNetworkId()),
                new NetworkId(entity.getPartnerNetworkId()), scopes, entity.getStatus(), entity.getEffectiveFrom(),
                entity.getEffectiveUntil(),
                entity.getSuspendedBy() == null ? null : new NetworkId(entity.getSuspendedBy()),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getVersion());
    }
}
