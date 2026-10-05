package com.nexusphere.authorization.contract;

import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.reference.ResourceReference;

import java.util.Objects;
import java.util.UUID;

public record AuthorizationRequest(PrincipalContext principal, String action, ResourceReference resource,
                                   ResourceOwner owner, UUID delegationId, String capabilityTypeCode) {

    public AuthorizationRequest {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(action, "action must not be null");
    }

    public static AuthorizationRequest of(PrincipalContext principal, String action, ResourceReference resource) {
        return new AuthorizationRequest(principal, action, resource, null, null, null);
    }

    public AuthorizationRequest withDelegation(UUID delegation) {
        return new AuthorizationRequest(principal, action, resource, owner, delegation, capabilityTypeCode);
    }

    public AuthorizationRequest withOwner(ResourceOwner resourceOwner) {
        return new AuthorizationRequest(principal, action, resource, resourceOwner, delegationId, capabilityTypeCode);
    }

    public AuthorizationRequest withCapabilityType(String typeCode) {
        return new AuthorizationRequest(principal, action, resource, owner, delegationId, typeCode);
    }

    public NetworkId targetNetworkId() {
        return resource == null ? principal.networkId() : resource.networkId();
    }
}
