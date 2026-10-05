package com.nexusphere.organization.contract;

import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

import java.util.Optional;

public interface OrganizationDirectory {

    Optional<OrganizationSnapshot> find(NetworkId networkId, OrganizationId id);
}
