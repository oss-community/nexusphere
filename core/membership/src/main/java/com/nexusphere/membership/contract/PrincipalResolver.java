package com.nexusphere.membership.contract;

import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.util.List;
import java.util.Optional;

public interface PrincipalResolver {

    PrincipalContext resolve(IdentityId identityId, NetworkId networkId);

    Optional<PrincipalContext> find(NetworkId networkId, PrincipalId principalId);

    List<NetworkId> memberNetworks(IdentityId identityId);
}
