package com.nexusphere.trust.domain.model;

import com.nexusphere.shared.domain.AggregateRoot;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.trust.contract.TrustEstablished;
import com.nexusphere.trust.contract.TrustRevoked;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TrustRelationship extends AggregateRoot<UUID> {

    private static final Pattern SCOPE = Pattern.compile("[a-z][a-z-]*:[a-z][a-z-]*");

    private final UUID id;
    private final Party source;
    private final Party target;
    private final Set<String> scopes;
    private final TrustLevel level;
    private TrustStatus status;
    private final Instant effectiveFrom;
    private final Instant effectiveUntil;
    private final Instant createdAt;
    private Instant revokedAt;
    private final Long version;

    private TrustRelationship(UUID id, Party source, Party target, Set<String> scopes, TrustLevel level,
                              TrustStatus status, Instant effectiveFrom, Instant effectiveUntil, Instant createdAt,
                              Instant revokedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.scopes = Collections.unmodifiableSet(new TreeSet<>(scopes));
        this.level = Objects.requireNonNull(level, "level must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        this.effectiveUntil = effectiveUntil;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.revokedAt = revokedAt;
        this.version = version;
    }

    public static TrustRelationship establish(UUID id, Party source, Party target, Collection<String> scopes,
                                              TrustLevel level, Instant effectiveFrom, Instant effectiveUntil,
                                              Instant now) {
        if (source.equals(target)) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "TRUST_WITH_ITSELF",
                    "A party cannot establish trust toward itself");
        }
        Set<String> normalized = normalize(scopes);
        Instant from = effectiveFrom == null ? now : effectiveFrom;
        if (effectiveUntil != null && (!effectiveUntil.isAfter(from) || !effectiveUntil.isAfter(now))) {
            throw new ValidationException("INVALID_TRUST_PERIOD",
                    "effectiveUntil must be after effectiveFrom and in the future");
        }
        TrustRelationship trust = new TrustRelationship(id, source, target, normalized, level, TrustStatus.ACTIVE,
                from, effectiveUntil, now, null, null);
        trust.registerEvent(new TrustEstablished(now, id, source.reference(), target.reference(), trust.scopes));
        return trust;
    }

    public static TrustRelationship restore(UUID id, Party source, Party target, Set<String> scopes, TrustLevel level,
                                            TrustStatus status, Instant effectiveFrom, Instant effectiveUntil,
                                            Instant createdAt, Instant revokedAt, Long version) {
        return new TrustRelationship(id, source, target, scopes, level, status, effectiveFrom, effectiveUntil,
                createdAt, revokedAt, version);
    }

    public void revoke(Instant now) {
        if (status == TrustStatus.REVOKED) {
            throw new ConflictException("TRUST_ALREADY_REVOKED", "Trust relationship " + id + " is already revoked");
        }
        status = TrustStatus.REVOKED;
        revokedAt = now;
        registerEvent(new TrustRevoked(now, id, source.reference(), target.reference()));
    }

    public boolean isEffective(Instant now) {
        return status == TrustStatus.ACTIVE && !now.isBefore(effectiveFrom)
                && (effectiveUntil == null || now.isBefore(effectiveUntil));
    }

    public boolean isCurrent(Instant now) {
        return status == TrustStatus.ACTIVE && (effectiveUntil == null || now.isBefore(effectiveUntil));
    }

    public boolean covers(String scope, Instant now) {
        return isEffective(now) && scopes.contains(scope);
    }

    public boolean involves(NetworkId networkId) {
        return source.networkId().equals(networkId) || target.networkId().equals(networkId);
    }

    private static Set<String> normalize(Collection<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new ValidationException("TRUST_SCOPE_REQUIRED", "At least one trust scope is required");
        }
        Set<String> normalized = new TreeSet<>();
        for (String scope : scopes) {
            String value = scope == null ? "" : scope.trim();
            if (!SCOPE.matcher(value).matches()) {
                throw new ValidationException("INVALID_TRUST_SCOPE",
                        "Trust scopes look like capability:discover, not " + scope);
            }
            normalized.add(value);
        }
        return normalized;
    }

    @Override
    public UUID id() {
        return id;
    }

    public Party source() {
        return source;
    }

    public Party target() {
        return target;
    }

    public Set<String> scopes() {
        return scopes;
    }

    public TrustLevel level() {
        return level;
    }

    public TrustStatus status() {
        return status;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Optional<Instant> effectiveUntil() {
        return Optional.ofNullable(effectiveUntil);
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
