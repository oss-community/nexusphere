package com.nexusphere.shared.context;

import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;

import java.util.Objects;
import java.util.Optional;

public record ExecutionContext(
        CorrelationId correlationId,
        IdentityId identityId,
        PrincipalId principalId,
        NetworkId networkId,
        OrganizationId organizationId) {

    public ExecutionContext {
        Objects.requireNonNull(correlationId, "correlationId must not be null");
    }

    public static ExecutionContext anonymous(CorrelationId correlationId) {
        return new ExecutionContext(correlationId, null, null, null, null);
    }

    public Optional<IdentityId> identity() {
        return Optional.ofNullable(identityId);
    }

    public Optional<PrincipalId> principal() {
        return Optional.ofNullable(principalId);
    }

    public Optional<NetworkId> network() {
        return Optional.ofNullable(networkId);
    }

    public Optional<OrganizationId> organization() {
        return Optional.ofNullable(organizationId);
    }

    public ExecutionContext withNetwork(NetworkId network) {
        return new ExecutionContext(correlationId, identityId, principalId, network, organizationId);
    }
}
