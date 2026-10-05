package com.nexusphere.authorization.contract;

import com.nexusphere.shared.id.NetworkId;

import java.util.Optional;

public interface FederationContextPort {

    Optional<FederationContext> findActive(NetworkId first, NetworkId second);
}
