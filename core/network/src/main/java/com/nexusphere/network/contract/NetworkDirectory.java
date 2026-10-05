package com.nexusphere.network.contract;

import com.nexusphere.shared.id.NetworkId;

import java.util.Optional;

public interface NetworkDirectory {

    Optional<NetworkSnapshot> find(NetworkId id);
}
