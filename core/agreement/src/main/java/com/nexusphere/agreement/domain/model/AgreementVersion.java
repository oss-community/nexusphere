package com.nexusphere.agreement.domain.model;

import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AgreementVersion {

    private final UUID id;
    private final int number;
    private final Map<String, Object> terms;
    private final List<String> changes;
    private final AgreementParty onBehalfOf;
    private final PrincipalId proposedBy;
    private final IdentityId proposedByIdentity;
    private final UUID delegationId;
    private final UUID decisionId;
    private final UUID federationId;
    private final Instant proposedAt;
    private PrincipalId acceptedBy;
    private Instant acceptedAt;
    private UUID acceptanceDecisionId;
    private boolean superseded;

    public AgreementVersion(UUID id, int number, Map<String, Object> terms, List<String> changes,
                            AgreementParty onBehalfOf, PrincipalId proposedBy, IdentityId proposedByIdentity,
                            UUID delegationId, UUID decisionId, UUID federationId, Instant proposedAt,
                            PrincipalId acceptedBy, Instant acceptedAt, UUID acceptanceDecisionId,
                            boolean superseded) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.number = number;
        this.terms = Collections.unmodifiableMap(new LinkedHashMap<>(terms));
        this.changes = List.copyOf(changes);
        this.onBehalfOf = Objects.requireNonNull(onBehalfOf, "onBehalfOf must not be null");
        this.proposedBy = Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        this.proposedByIdentity = Objects.requireNonNull(proposedByIdentity, "proposedByIdentity must not be null");
        this.delegationId = delegationId;
        this.decisionId = decisionId;
        this.federationId = federationId;
        this.proposedAt = Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        this.acceptedBy = acceptedBy;
        this.acceptedAt = acceptedAt;
        this.acceptanceDecisionId = acceptanceDecisionId;
        this.superseded = superseded;
    }

    void accept(PrincipalId principalId, UUID decision, Instant now) {
        acceptedBy = principalId;
        acceptedAt = now;
        acceptanceDecisionId = decision;
    }

    void supersede() {
        superseded = true;
    }

    public UUID id() {
        return id;
    }

    public int number() {
        return number;
    }

    public Map<String, Object> terms() {
        return terms;
    }

    public List<String> changes() {
        return changes;
    }

    public AgreementParty onBehalfOf() {
        return onBehalfOf;
    }

    public PrincipalId proposedBy() {
        return proposedBy;
    }

    public IdentityId proposedByIdentity() {
        return proposedByIdentity;
    }

    public Optional<UUID> delegationId() {
        return Optional.ofNullable(delegationId);
    }

    public Optional<UUID> decisionId() {
        return Optional.ofNullable(decisionId);
    }

    public Optional<UUID> federationId() {
        return Optional.ofNullable(federationId);
    }

    public Instant proposedAt() {
        return proposedAt;
    }

    public Optional<PrincipalId> acceptedBy() {
        return Optional.ofNullable(acceptedBy);
    }

    public Optional<Instant> acceptedAt() {
        return Optional.ofNullable(acceptedAt);
    }

    public Optional<UUID> acceptanceDecisionId() {
        return Optional.ofNullable(acceptanceDecisionId);
    }

    public boolean superseded() {
        return superseded;
    }
}
