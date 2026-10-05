package com.nexusphere.discovery.domain.repository;

import com.nexusphere.capability.contract.CapabilitySnapshot;
import com.nexusphere.shared.id.NetworkId;

import java.util.List;

public interface CapabilityDiscoveryPort {

    List<CapabilitySnapshot> findCapabilities(NetworkId networkId);
}
