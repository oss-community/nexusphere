package com.nexusphere.integration.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface LedgerGrantJpaRepository extends JpaRepository<LedgerGrantEntity, String> {

    Optional<LedgerGrantEntity> findByDelegationIdAndRevokedAtIsNull(UUID delegationId);
}
