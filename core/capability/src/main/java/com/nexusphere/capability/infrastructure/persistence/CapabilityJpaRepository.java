package com.nexusphere.capability.infrastructure.persistence;

import com.nexusphere.capability.domain.model.CapabilityStatus;
import com.nexusphere.capability.domain.model.OwnerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CapabilityJpaRepository extends JpaRepository<CapabilityEntity, UUID> {

    Optional<CapabilityEntity> findByIdAndNetworkId(UUID id, UUID networkId);

    List<CapabilityEntity> findByNetworkIdOrderByCreatedAtAscIdAsc(UUID networkId);

    @Query("""
            select count(c) > 0 from CapabilityEntity c
            where c.networkId = :networkId and c.ownerType = :ownerType and c.ownerId = :ownerId
              and lower(c.name) = lower(:name) and c.status <> :withdrawn
            """)
    boolean existsCurrentName(UUID networkId, OwnerType ownerType, UUID ownerId, String name,
                              CapabilityStatus withdrawn);
}
