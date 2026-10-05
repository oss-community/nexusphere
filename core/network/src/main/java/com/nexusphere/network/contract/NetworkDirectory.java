package com.nexusphere.network.contract;

import com.nexusphere.shared.id.NetworkId;

import java.util.List;
import java.util.Optional;

public interface NetworkDirectory {

    Optional<NetworkSnapshot> find(NetworkId id);

    List<NetworkSnapshot> findAllActive();
}
