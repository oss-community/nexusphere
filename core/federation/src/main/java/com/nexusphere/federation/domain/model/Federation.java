package com.nexusphere.federation.domain.model;

import com.nexusphere.federation.contract.FederationActivated;
import com.nexusphere.federation.contract.FederationProposed;
import com.nexusphere.federation.contract.FederationRejected;
import com.nexusphere.federation.contract.FederationResumed;
import com.nexusphere.federation.contract.FederationSubmitted;
import com.nexusphere.federation.contract.FederationSuspended;
import com.nexusphere.federation.contract.FederationTerminated;
import com.nexusphere.shared.domain.AggregateRoot;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class Federation extends AggregateRoot<UUID> {

    private final UUID id;
    private final NetworkId proposerNetworkId;
    private final NetworkId partnerNetworkId;
    private final Set<FederationScope> scopes;
    private FederationStatus status;
    private Instant effectiveFrom;
    private final Instant effectiveUntil;
    private NetworkId suspendedBy;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    private Federation(UUID id, NetworkId proposerNetworkId, NetworkId partnerNetworkId, Set<FederationScope> scopes,
                       FederationStatus status, Instant effectiveFrom, Instant effectiveUntil, NetworkId suspendedBy,
                       Instant createdAt, Instant updatedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.proposerNetworkId = Objects.requireNonNull(proposerNetworkId, "proposerNetworkId must not be null");
        this.partnerNetworkId = Objects.requireNonNull(partnerNetworkId, "partnerNetworkId must not be null");
        this.scopes = Collections.unmodifiableSet(EnumSet.copyOf(scopes));
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
        this.suspendedBy = suspendedBy;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    public static Federation propose(UUID id, NetworkId proposer, NetworkId partner,
                                     Collection<FederationScope> scopes, Instant effectiveUntil, Instant now) {
        if (proposer.equals(partner)) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "FEDERATION_WITH_ITSELF",
                    "A network cannot federate with itself");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new ValidationException("FEDERATION_SCOPE_REQUIRED", "At least one federation scope is required");
        }
        if (effectiveUntil != null && !effectiveUntil.isAfter(now)) {
            throw new ValidationException("INVALID_FEDERATION_PERIOD", "effectiveUntil must be in the future");
        }
        Federation federation = new Federation(id, proposer, partner, EnumSet.copyOf(scopes),
                FederationStatus.PROPOSED, null, effectiveUntil, null, now, now, null);
        federation.registerEvent(new FederationProposed(now, id, proposer, partner));
        return federation;
    }

    public static Federation restore(UUID id, NetworkId proposerNetworkId, NetworkId partnerNetworkId,
                                     Set<FederationScope> scopes, FederationStatus status, Instant effectiveFrom,
                                     Instant effectiveUntil, NetworkId suspendedBy, Instant createdAt,
                                     Instant updatedAt, Long version) {
        return new Federation(id, proposerNetworkId, partnerNetworkId, scopes, status, effectiveFrom, effectiveUntil,
                suspendedBy, createdAt, updatedAt, version);
    }

    public void submit(NetworkId by, Instant now) {
        requireParty(by, proposerNetworkId, "Only the proposing network can submit the federation");
        transition(FederationStatus.PROPOSED, FederationStatus.PENDING_ACCEPTANCE, now);
        registerEvent(new FederationSubmitted(now, id, by, partnerNetworkId));
    }

    public void accept(NetworkId by, Instant now) {
        requireParty(by, partnerNetworkId, "Only the partner network can accept the federation");
        transition(FederationStatus.PENDING_ACCEPTANCE, FederationStatus.ACTIVE, now);
        effectiveFrom = now;
        registerEvent(new FederationActivated(now, id, by, proposerNetworkId));
    }

    public void reject(NetworkId by, Instant now) {
        requireParty(by, partnerNetworkId, "Only the partner network can reject the federation");
        transition(FederationStatus.PENDING_ACCEPTANCE, FederationStatus.REJECTED, now);
        registerEvent(new FederationRejected(now, id, by, proposerNetworkId));
    }

    public void suspend(NetworkId by, Instant now) {
        requireMember(by);
        transition(FederationStatus.ACTIVE, FederationStatus.SUSPENDED, now);
        suspendedBy = by;
        registerEvent(new FederationSuspended(now, id, by, counterpart(by)));
    }

    public void resume(NetworkId by, Instant now) {
        requireMember(by);
        if (status == FederationStatus.SUSPENDED && !by.equals(suspendedBy)) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "FEDERATION_WRONG_PARTY",
                    "Only the network that suspended the federation can resume it");
        }
        transition(FederationStatus.SUSPENDED, FederationStatus.ACTIVE, now);
        suspendedBy = null;
        registerEvent(new FederationResumed(now, id, by, counterpart(by)));
    }

    public void terminate(NetworkId by, Instant now) {
        requireMember(by);
        if (status.isTerminal()) {
            throw invalidTransition(FederationStatus.TERMINATED);
        }
        status = FederationStatus.TERMINATED;
        updatedAt = now;
        registerEvent(new FederationTerminated(now, id, by, counterpart(by)));
    }

    public boolean isActive(Instant now) {
        return status == FederationStatus.ACTIVE && (effectiveUntil == null || now.isBefore(effectiveUntil));
    }

    public boolean involves(NetworkId networkId) {
        return proposerNetworkId.equals(networkId) || partnerNetworkId.equals(networkId);
    }

    public NetworkId counterpart(NetworkId networkId) {
        return proposerNetworkId.equals(networkId) ? partnerNetworkId : proposerNetworkId;
    }

    private void transition(FederationStatus from, FederationStatus to, Instant now) {
        if (status != from) {
            throw invalidTransition(to);
        }
        status = to;
        updatedAt = now;
    }

    private ConflictException invalidTransition(FederationStatus to) {
        return new ConflictException("FEDERATION_INVALID_TRANSITION",
                "Federation " + id + " cannot move from " + status + " to " + to);
    }

    private void requireMember(NetworkId by) {
        if (!involves(by)) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "FEDERATION_WRONG_PARTY",
                    "Network " + by + " is not part of federation " + id);
        }
    }

    private static void requireParty(NetworkId by, NetworkId expected, String message) {
        if (!by.equals(expected)) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "FEDERATION_WRONG_PARTY", message);
        }
    }

    @Override
    public UUID id() {
        return id;
    }

    public NetworkId proposerNetworkId() {
        return proposerNetworkId;
    }

    public NetworkId partnerNetworkId() {
        return partnerNetworkId;
    }

    public Set<FederationScope> scopes() {
        return scopes;
    }

    public FederationStatus status() {
        return status;
    }

    public Optional<Instant> effectiveFrom() {
        return Optional.ofNullable(effectiveFrom);
    }

    public Optional<Instant> effectiveUntil() {
        return Optional.ofNullable(effectiveUntil);
    }

    public Optional<NetworkId> suspendedBy() {
        return Optional.ofNullable(suspendedBy);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long version() {
        return version;
    }
}
