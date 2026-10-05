package com.nexusphere.authorization.domain.repository;

import com.nexusphere.authorization.domain.model.RoleAssignment;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleAssignmentRepository {

    RoleAssignment save(RoleAssignment assignment);

    Optional<RoleAssignment> findById(NetworkId networkId, UUID id);

    List<RoleAssignment> findAll(NetworkId networkId);

    List<RoleAssignment> findActive(NetworkId networkId, PrincipalId principalId);
}
