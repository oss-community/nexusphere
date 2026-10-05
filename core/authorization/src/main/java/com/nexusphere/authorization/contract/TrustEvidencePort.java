package com.nexusphere.authorization.contract;

import com.nexusphere.shared.id.NetworkId;

import java.util.Optional;
import java.util.UUID;

public interface TrustEvidencePort {

    Optional<UUID> findEffective(NetworkId trustingNetwork, NetworkId trustedNetwork, String scope);
}
