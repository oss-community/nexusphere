package com.nexusphere.transaction.contract;

import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;

import java.util.Map;
import java.util.UUID;

public record TransactionSnapshot(UUID id, String type, String status, String reason, UUID agreementId,
                                  int agreementVersion, CapabilityId capabilityId, NetworkId capabilityNetworkId,
                                  OrganizationId requesterOrganizationId, NetworkId requesterNetworkId,
                                  OrganizationId providerOrganizationId, NetworkId providerNetworkId,
                                  PrincipalId initiatingPrincipalId, IdentityId initiatingIdentityId,
                                  UUID decisionId, UUID delegationId, UUID federationId,
                                  PrincipalId executorPrincipalId, IdentityId executorIdentityId,
                                  Map<String, Object> metadata, Map<String, Object> result) {
}
