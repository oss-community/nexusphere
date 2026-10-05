package com.nexusphere.transaction.domain.model;

import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;

public record Participant(PrincipalId principalId, IdentityId identityId, NetworkId networkId,
                          OrganizationId organizationId, boolean networkAdministrator) {
}
