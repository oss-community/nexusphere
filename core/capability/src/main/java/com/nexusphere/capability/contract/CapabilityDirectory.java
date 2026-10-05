package com.nexusphere.capability.contract;

import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;

import java.util.List;
import java.util.Optional;

public interface CapabilityDirectory {

    Optional<CapabilitySnapshot> find(NetworkId networkId, CapabilityId id);

    List<CapabilitySnapshot> findAvailable(NetworkId networkId);
}
