package com.nexusphere.integration.infrastructure.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface OutboxJpaRepository extends JpaRepository<OutboxEntity, UUID> {

    List<OutboxEntity> findBySentAtIsNullAndRejectedAtIsNullOrderBySeqAsc(Limit limit);

    long countBySentAtIsNullAndRejectedAtIsNull();
}
