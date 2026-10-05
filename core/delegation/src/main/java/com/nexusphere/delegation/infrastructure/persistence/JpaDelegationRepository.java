package com.nexusphere.delegation.infrastructure.persistence;

import com.nexusphere.delegation.domain.model.Delegation;
import com.nexusphere.delegation.domain.model.DelegationConstraints;
import com.nexusphere.delegation.domain.repository.DelegationRepository;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
class JpaDelegationRepository implements DelegationRepository {

    private final DelegationJpaRepository jpa;

    JpaDelegationRepository(DelegationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Delegation save(Delegation delegation) {
        try {
            return toDomain(jpa.saveAndFlush(toEntity(delegation)));
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("CONCURRENT_MODIFICATION", "Delegation " + delegation.id() + " was changed concurrently");
        }
    }

    @Override
    public Optional<Delegation> findById(UUID id) {
        return jpa.findById(id).map(JpaDelegationRepository::toDomain);
    }

    @Override
    public List<Delegation> findByNetwork(NetworkId networkId) {
        return jpa.findByNetworkIdOrderByCreatedAtAscIdAsc(networkId.value()).stream()
                .map(JpaDelegationRepository::toDomain).toList();
    }

    @Override
    public List<Delegation> findGrantedTo(NetworkId networkId, PrincipalId delegatePrincipalId) {
        return jpa.findByNetworkIdAndDelegatePrincipalId(networkId.value(), delegatePrincipalId.value()).stream()
                .map(JpaDelegationRepository::toDomain).toList();
    }

    private static DelegationEntity toEntity(Delegation delegation) {
        DelegationConstraints constraints = delegation.constraints();
        return new DelegationEntity(delegation.id(), delegation.networkId().value(),
                delegation.delegatorPrincipalId().value(), delegation.delegatePrincipalId().value(),
                join(delegation.actions()), join(constraints.capabilityTypes()),
                join(constraints.networks().stream().map(NetworkId::toString).toList()),
                join(constraints.resourceTypes()), delegation.status(), delegation.validFrom(),
                delegation.validUntil().orElse(null), delegation.createdAt(), delegation.revokedAt().orElse(null),
                delegation.version());
    }

    private static Delegation toDomain(DelegationEntity entity) {
        DelegationConstraints constraints = new DelegationConstraints(split(entity.getCapabilityTypes()),
                split(entity.getNetworks()).stream().map(NetworkId::of).collect(Collectors.toSet()),
                split(entity.getResourceTypes()));
        return Delegation.restore(entity.getId(), new NetworkId(entity.getNetworkId()),
                new PrincipalId(entity.getDelegatorPrincipalId()), new PrincipalId(entity.getDelegatePrincipalId()),
                split(entity.getActions()), constraints, entity.getStatus(), entity.getValidFrom(),
                entity.getValidUntil(), entity.getCreatedAt(), entity.getRevokedAt(), entity.getVersion());
    }

    private static String join(Collection<String> values) {
        return values.stream().sorted().collect(Collectors.joining(" "));
    }

    private static Set<String> split(String value) {
        return Arrays.stream(value.split(" ")).filter(part -> !part.isBlank()).collect(Collectors.toSet());
    }
}
