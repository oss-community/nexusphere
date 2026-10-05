package com.nexusphere.agreement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface AgreementJpaRepository extends JpaRepository<AgreementEntity, UUID> {

    @Query("""
            select a from AgreementEntity a
            where a.proposerNetworkId = :networkId or a.counterpartyNetworkId = :networkId
            order by a.createdAt, a.id
            """)
    List<AgreementEntity> findInvolving(UUID networkId);
}
