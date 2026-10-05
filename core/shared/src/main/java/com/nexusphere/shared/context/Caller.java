package com.nexusphere.shared.context;

import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;

import java.util.Set;

public record Caller(IdentityId identityId, boolean operator, Set<NetworkId> memberNetworks,
                     Set<NetworkId> administeredNetworks) {

    public Caller {
        memberNetworks = memberNetworks == null ? Set.of() : Set.copyOf(memberNetworks);
        administeredNetworks = administeredNetworks == null ? Set.of() : Set.copyOf(administeredNetworks);
    }

    public static Caller anonymous() {
        return new Caller(null, false, Set.of(), Set.of());
    }

    public static Caller platformOperator() {
        return new Caller(null, true, Set.of(), Set.of());
    }

    public boolean authenticated() {
        return operator || identityId != null;
    }

    public boolean is(IdentityId identity) {
        return identityId != null && identityId.equals(identity);
    }

    public boolean administers(NetworkId networkId) {
        return operator || administeredNetworks.contains(networkId);
    }

    public boolean memberOf(NetworkId networkId) {
        return operator || memberNetworks.contains(networkId);
    }

    public void requireOperator() {
        requireAuthenticated();
        if (!operator) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "PLATFORM_OPERATOR_REQUIRED",
                    "Only the platform operator can perform this operation");
        }
    }

    public void requireAdministratorOf(NetworkId networkId) {
        requireAuthenticated();
        if (!administers(networkId)) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "NETWORK_ADMINISTRATOR_REQUIRED",
                    "Only an administrator of network " + networkId + " can perform this operation");
        }
    }

    public void requireMemberOf(NetworkId networkId) {
        requireAuthenticated();
        if (!memberOf(networkId)) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "NETWORK_ACCESS_DENIED",
                    "The caller has no active membership in network " + networkId);
        }
    }

    private void requireAuthenticated() {
        if (!authenticated()) {
            throw new DomainException(ErrorCategory.AUTHENTICATION_ERROR, "AUTHENTICATION_REQUIRED",
                    "A bearer token is required");
        }
    }
}
