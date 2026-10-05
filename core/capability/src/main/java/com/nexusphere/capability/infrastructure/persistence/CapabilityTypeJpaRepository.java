package com.nexusphere.capability.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CapabilityTypeJpaRepository extends JpaRepository<CapabilityTypeEntity, UUID> {

    Optional<CapabilityTypeEntity> findByCodeAndTypeVersion(String code, int typeVersion);

    Optional<CapabilityTypeEntity> findFirstByCodeOrderByTypeVersionDesc(String code);

    List<CapabilityTypeEntity> findAllByOrderByCodeAscTypeVersionAsc();

    List<CapabilityTypeEntity> findByCodeOrderByTypeVersionAsc(String code);
}
