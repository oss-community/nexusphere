package com.nexusphere.discovery.infrastructure.directory;

import com.nexusphere.capability.contract.CapabilityDirectory;
import com.nexusphere.capability.contract.CapabilitySnapshot;
import com.nexusphere.discovery.domain.repository.CapabilityDiscoveryPort;
import com.nexusphere.shared.id.NetworkId;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class DirectoryCapabilityDiscovery implements CapabilityDiscoveryPort {

    private final CapabilityDirectory capabilities;

    DirectoryCapabilityDiscovery(CapabilityDirectory capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public List<CapabilitySnapshot> findCapabilities(NetworkId networkId) {
        return capabilities.findAvailable(networkId);
    }
}
