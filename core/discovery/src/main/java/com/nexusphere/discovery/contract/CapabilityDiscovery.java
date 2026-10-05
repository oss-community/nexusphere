package com.nexusphere.discovery.contract;

import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.id.CapabilityId;

import java.util.List;
import java.util.Optional;

public interface CapabilityDiscovery {

    List<DiscoveredCapability> discover(PrincipalContext principal, CapabilityQuery query, ExecutionContext context);

    Optional<DiscoveredCapability> find(PrincipalContext principal, CapabilityId capabilityId,
                                        ExecutionContext context);
}
