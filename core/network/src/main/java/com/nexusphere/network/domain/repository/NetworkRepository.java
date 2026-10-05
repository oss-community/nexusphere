package com.nexusphere.network.domain.repository;

import com.nexusphere.network.domain.model.Network;
import com.nexusphere.shared.id.NetworkId;

import java.util.List;
import java.util.Optional;

public interface NetworkRepository {

    Network save(Network network);

    Optional<Network> findById(NetworkId id);

    List<Network> findAll();

    boolean existsByName(String name);
}
