package com.nexusphere.transaction.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface TransactionJpaRepository extends JpaRepository<TransactionEntity, UUID> {

    @Query("""
            select t from TransactionEntity t
            where t.requesterNetworkId = :networkId or t.providerNetworkId = :networkId
            order by t.createdAt, t.id
            """)
    List<TransactionEntity> findInvolving(UUID networkId);
}
