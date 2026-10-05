package com.nexusphere.membership.contract;

import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;

public interface PrincipalResolver {

    PrincipalContext resolve(IdentityId identityId, NetworkId networkId);
}
