package com.nexusphere.authorization.infrastructure.persistence;

import com.nexusphere.authorization.domain.model.RoleAssignment;
import com.nexusphere.authorization.domain.model.RoleAssignmentStatus;
import com.nexusphere.authorization.domain.repository.RoleAssignmentRepository;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaRoleAssignmentRepository implements RoleAssignmentRepository {

    private final RoleAssignmentJpaRepository jpa;

    JpaRoleAssignmentRepository(RoleAssignmentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public RoleAssignment save(RoleAssignment assignment) {
        try {
            return toDomain(jpa.saveAndFlush(new RoleAssignmentEntity(assignment.id(), assignment.networkId().value(),
                    assignment.principalId().value(), assignment.role(), assignment.status(),
                    assignment.assignedBy().map(PrincipalId::value).orElse(null), assignment.assignedAt(),
                    assignment.revokedAt().orElse(null), assignment.version())));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("ROLE_ALREADY_ASSIGNED",
                    "Principal " + assignment.principalId() + " already holds " + assignment.role());
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("CONCURRENT_MODIFICATION", "Role assignment " + assignment.id() + " was changed concurrently");
        }
    }

    @Override
    public Optional<RoleAssignment> findById(NetworkId networkId, UUID id) {
        return jpa.findByIdAndNetworkId(id, networkId.value()).map(JpaRoleAssignmentRepository::toDomain);
    }

    @Override
    public List<RoleAssignment> findAll(NetworkId networkId) {
        return jpa.findByNetworkIdOrderByAssignedAtAscIdAsc(networkId.value()).stream()
                .map(JpaRoleAssignmentRepository::toDomain).toList();
    }

    @Override
    public List<RoleAssignment> findActive(NetworkId networkId, PrincipalId principalId) {
        return jpa.findByNetworkIdAndPrincipalIdAndStatus(networkId.value(), principalId.value(),
                RoleAssignmentStatus.ACTIVE).stream().map(JpaRoleAssignmentRepository::toDomain).toList();
    }

    private static RoleAssignment toDomain(RoleAssignmentEntity entity) {
        return RoleAssignment.restore(entity.getId(), new NetworkId(entity.getNetworkId()),
                new PrincipalId(entity.getPrincipalId()), entity.getRole(), entity.getStatus(),
                entity.getAssignedBy() == null ? null : new PrincipalId(entity.getAssignedBy()),
                entity.getAssignedAt(), entity.getRevokedAt(), entity.getVersion());
    }
}
