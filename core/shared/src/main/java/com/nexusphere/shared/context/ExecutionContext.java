package com.nexusphere.shared.context;

import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;

import java.util.Objects;
import java.util.Optional;

/**
 * The context every scoped operation carries (redesign §57).
 *
 * <p>A network ID in here has been verified by the platform; it is never taken from the client
 * as-is. Membership, federation and delegation references are added by their modules as those
 * modules are implemented.
 */
public record ExecutionContext(
        CorrelationId correlationId,
        IdentityId identityId,
        PrincipalId principalId,
        NetworkId networkId,
        OrganizationId organizationId) {

    public ExecutionContext {
        Objects.requireNonNull(correlationId, "correlationId must not be null");
    }

    /** Context for work that is not yet attributed to any identity, e.g. an unauthenticated request. */
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
