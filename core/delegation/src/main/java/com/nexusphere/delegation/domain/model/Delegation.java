package com.nexusphere.delegation.domain.model;

import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.delegation.contract.DelegationGranted;
import com.nexusphere.delegation.contract.DelegationResumed;
import com.nexusphere.delegation.contract.DelegationRevoked;
import com.nexusphere.delegation.contract.DelegationSuspended;
import com.nexusphere.shared.domain.AggregateRoot;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class Delegation extends AggregateRoot<UUID> {

    private final UUID id;
    private final NetworkId networkId;
    private final PrincipalId delegatorPrincipalId;
    private final PrincipalId delegatePrincipalId;
    private final Set<String> actions;
    private final DelegationConstraints constraints;
    private DelegationStatus status;
    private final Instant validFrom;
    private final Instant validUntil;
    private final Instant createdAt;
    private Instant revokedAt;
    private final Long version;

    private Delegation(UUID id, NetworkId networkId, PrincipalId delegatorPrincipalId, PrincipalId delegatePrincipalId,
                       Set<String> actions, DelegationConstraints constraints, DelegationStatus status,
                       Instant validFrom, Instant validUntil, Instant createdAt, Instant revokedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.networkId = Objects.requireNonNull(networkId, "networkId must not be null");
        this.delegatorPrincipalId = Objects.requireNonNull(delegatorPrincipalId, "delegator must not be null");
        this.delegatePrincipalId = Objects.requireNonNull(delegatePrincipalId, "delegate must not be null");
        this.actions = Collections.unmodifiableSet(new TreeSet<>(actions));
        this.constraints = Objects.requireNonNull(constraints, "constraints must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom must not be null");
        this.validUntil = validUntil;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.revokedAt = revokedAt;
        this.version = version;
    }

    public static Delegation grant(UUID id, NetworkId networkId, PrincipalId delegator, PrincipalId delegate,
                                   Collection<String> actions, DelegationConstraints constraints, Instant validFrom,
                                   Instant validUntil, Instant now) {
        if (delegator.equals(delegate)) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "DELEGATION_TO_SELF",
                    "A principal cannot delegate authority to itself");
        }
        Set<String> normalized = normalize(actions);
        Instant from = validFrom == null ? now : validFrom;
        if (validUntil != null && (!validUntil.isAfter(from) || !validUntil.isAfter(now))) {
            throw new ValidationException("INVALID_DELEGATION_PERIOD",
                    "validUntil must be after validFrom and in the future");
        }
        Delegation delegation = new Delegation(id, networkId, delegator, delegate, normalized,
                constraints == null ? DelegationConstraints.none() : constraints, DelegationStatus.ACTIVE, from,
                validUntil, now, null, null);
        delegation.registerEvent(new DelegationGranted(now, id, networkId, delegator, delegate, delegation.actions));
        return delegation;
    }

    public static Delegation restore(UUID id, NetworkId networkId, PrincipalId delegator, PrincipalId delegate,
                                     Set<String> actions, DelegationConstraints constraints, DelegationStatus status,
                                     Instant validFrom, Instant validUntil, Instant createdAt, Instant revokedAt,
                                     Long version) {
        return new Delegation(id, networkId, delegator, delegate, actions, constraints, status, validFrom, validUntil,
                createdAt, revokedAt, version);
    }

    public void revoke(Instant now) {
        if (status == DelegationStatus.REVOKED) {
            throw new ConflictException("DELEGATION_ALREADY_REVOKED", "Delegation " + id + " is already revoked");
        }
        status = DelegationStatus.REVOKED;
        revokedAt = now;
        registerEvent(new DelegationRevoked(now, id, networkId, delegatorPrincipalId, delegatePrincipalId));
    }

    public void suspend(Instant now) {
        requireStatus(DelegationStatus.ACTIVE, "suspend");
        status = DelegationStatus.SUSPENDED;
        registerEvent(new DelegationSuspended(now, id, networkId, delegatorPrincipalId, delegatePrincipalId));
    }

    public void resume(Instant now) {
        requireStatus(DelegationStatus.SUSPENDED, "resume");
        status = DelegationStatus.ACTIVE;
        registerEvent(new DelegationResumed(now, id, networkId, delegatorPrincipalId, delegatePrincipalId));
    }

    public DelegationStatus effectiveStatus(Instant now) {
        if (status == DelegationStatus.ACTIVE && isExpired(now)) {
            return DelegationStatus.EXPIRED;
        }
        return status;
    }

    public boolean isEffective(Instant now) {
        return status == DelegationStatus.ACTIVE && !now.isBefore(validFrom) && !isExpired(now);
    }

    public boolean involves(PrincipalId principalId) {
        return delegatorPrincipalId.equals(principalId) || delegatePrincipalId.equals(principalId);
    }

    private boolean isExpired(Instant now) {
        return validUntil != null && !now.isBefore(validUntil);
    }

    private void requireStatus(DelegationStatus expected, String transition) {
        if (status != expected) {
            throw new ConflictException("DELEGATION_INVALID_TRANSITION",
                    "Cannot " + transition + " delegation " + id + " in status " + status);
        }
    }

    private static Set<String> normalize(Collection<String> actions) {
        if (actions == null || actions.isEmpty()) {
            throw new ValidationException("DELEGATION_ACTIONS_REQUIRED", "At least one action must be delegated");
        }
        Set<String> normalized = new TreeSet<>();
        for (String action : actions) {
            String value = action == null ? "" : action.trim();
            if (!Actions.ALL.contains(value)) {
                throw new ValidationException("UNKNOWN_ACTION", "Unknown action " + action);
            }
            normalized.add(value);
        }
        if (normalized.contains(Actions.DELEGATION_GRANT)) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "DELEGATION_DEPTH_EXCEEDED",
                    "The authority to grant delegations cannot itself be delegated");
        }
        return normalized;
    }

    @Override
    public UUID id() {
        return id;
    }

    public NetworkId networkId() {
        return networkId;
    }

    public PrincipalId delegatorPrincipalId() {
        return delegatorPrincipalId;
    }

    public PrincipalId delegatePrincipalId() {
        return delegatePrincipalId;
    }

    public Set<String> actions() {
        return actions;
    }

    public DelegationConstraints constraints() {
        return constraints;
    }

    public DelegationStatus status() {
        return status;
    }

    public Instant validFrom() {
        return validFrom;
    }

    public Optional<Instant> validUntil() {
        return Optional.ofNullable(validUntil);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> revokedAt() {
        return Optional.ofNullable(revokedAt);
    }

    public Long version() {
        return version;
    }
}
