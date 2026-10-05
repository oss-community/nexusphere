package com.nexusphere.authorization.contract;

import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.OrganizationId;

public record ResourceOwner(OrganizationId organizationId, IdentityId identityId) {
}
