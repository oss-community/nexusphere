package com.nexusphere.federation.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface FederationJpaRepository extends JpaRepository<FederationEntity, UUID> {

    @Query("""
            select f from FederationEntity f
            where f.proposerNetworkId = :networkId or f.partnerNetworkId = :networkId
            order by f.createdAt, f.id
            """)
    List<FederationEntity> findInvolving(UUID networkId);

    @Query("""
            select f from FederationEntity f
            where (f.proposerNetworkId = :first and f.partnerNetworkId = :second)
               or (f.proposerNetworkId = :second and f.partnerNetworkId = :first)
            order by f.createdAt, f.id
            """)
    List<FederationEntity> findBetween(UUID first, UUID second);
}
