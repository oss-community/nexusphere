package com.nexusphere.authorization.contract;

import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DelegationEvidencePort {

    Optional<DelegationEvidence> find(UUID delegationId);

    List<DelegationEvidence> findGrantedTo(NetworkId networkId, PrincipalId delegatePrincipalId);
}
