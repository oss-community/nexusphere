package com.nexusphere.delegation.domain.repository;

import com.nexusphere.delegation.domain.model.Delegation;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DelegationRepository {

    Delegation save(Delegation delegation);

    Optional<Delegation> findById(UUID id);

    List<Delegation> findByNetwork(NetworkId networkId);

    List<Delegation> findGrantedTo(NetworkId networkId, PrincipalId delegatePrincipalId);
}
