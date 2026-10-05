package com.nexusphere.audit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AuditEventJpaRepository extends JpaRepository<AuditEventEntity, UUID> {

    Optional<AuditEventEntity> findByIdAndNetworkId(UUID id, UUID networkId);

    List<AuditEventEntity> findByNetworkIdOrderByOccurredAtAscRecordedOrderAsc(UUID networkId);
}
