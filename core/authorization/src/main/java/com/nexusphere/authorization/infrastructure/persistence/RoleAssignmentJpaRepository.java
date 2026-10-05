package com.nexusphere.authorization.infrastructure.persistence;

import com.nexusphere.authorization.domain.model.RoleAssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface RoleAssignmentJpaRepository extends JpaRepository<RoleAssignmentEntity, UUID> {

    Optional<RoleAssignmentEntity> findByIdAndNetworkId(UUID id, UUID networkId);

    List<RoleAssignmentEntity> findByNetworkIdOrderByAssignedAtAscIdAsc(UUID networkId);

    List<RoleAssignmentEntity> findByNetworkIdAndPrincipalIdAndStatus(UUID networkId, UUID principalId,
                                                                      RoleAssignmentStatus status);
}
