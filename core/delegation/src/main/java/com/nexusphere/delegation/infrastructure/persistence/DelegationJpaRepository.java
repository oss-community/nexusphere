package com.nexusphere.delegation.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface DelegationJpaRepository extends JpaRepository<DelegationEntity, UUID> {

    List<DelegationEntity> findByNetworkIdOrderByCreatedAtAscIdAsc(UUID networkId);

    List<DelegationEntity> findByNetworkIdAndDelegatePrincipalId(UUID networkId, UUID delegatePrincipalId);
}
