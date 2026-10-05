package com.nexusphere.federation.domain.repository;

import com.nexusphere.federation.domain.model.Federation;
import com.nexusphere.shared.id.NetworkId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FederationRepository {

    Federation save(Federation federation);

    Optional<Federation> findById(UUID id);

    List<Federation> findInvolving(NetworkId networkId);

    List<Federation> findBetween(NetworkId first, NetworkId second);
}
