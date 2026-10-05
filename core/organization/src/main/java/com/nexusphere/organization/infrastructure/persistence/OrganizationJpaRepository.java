package com.nexusphere.organization.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface OrganizationJpaRepository extends JpaRepository<OrganizationEntity, UUID> {

    Optional<OrganizationEntity> findByIdAndNetworkId(UUID id, UUID networkId);

    List<OrganizationEntity> findByNetworkIdOrderByCreatedAtAscIdAsc(UUID networkId);

    boolean existsByNetworkIdAndNameIgnoreCase(UUID networkId, String name);
}
