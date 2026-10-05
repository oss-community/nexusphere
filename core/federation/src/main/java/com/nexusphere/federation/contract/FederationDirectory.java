package com.nexusphere.federation.contract;

import com.nexusphere.shared.id.NetworkId;

import java.util.Optional;

public interface FederationDirectory {

    Optional<FederationSnapshot> findActive(NetworkId first, NetworkId second);
}
