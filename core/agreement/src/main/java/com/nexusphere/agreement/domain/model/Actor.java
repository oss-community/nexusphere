package com.nexusphere.agreement.domain.model;

import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;

import java.util.UUID;

public record Actor(PrincipalId principalId, IdentityId identityId, NetworkId networkId,
                    OrganizationId organizationId, boolean networkAdministrator, UUID delegationId, UUID decisionId,
                    UUID federationId) {
}
