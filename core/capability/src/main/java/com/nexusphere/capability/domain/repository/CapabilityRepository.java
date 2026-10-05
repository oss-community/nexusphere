package com.nexusphere.capability.domain.repository;

import com.nexusphere.capability.domain.model.Capability;
import com.nexusphere.capability.domain.model.CapabilityOwner;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;

import java.util.List;
import java.util.Optional;

public interface CapabilityRepository {

    Capability save(Capability capability);

    Optional<Capability> findById(NetworkId networkId, CapabilityId id);

    List<Capability> findAll(NetworkId networkId);

    boolean existsCurrentName(NetworkId networkId, CapabilityOwner owner, String name);
}
