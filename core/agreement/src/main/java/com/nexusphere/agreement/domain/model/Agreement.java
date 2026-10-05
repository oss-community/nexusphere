package com.nexusphere.agreement.domain.model;

import com.nexusphere.agreement.contract.AgreementChanged;
import com.nexusphere.shared.domain.AggregateRoot;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class Agreement extends AggregateRoot<UUID> {

    public static final int TITLE_MAX_LENGTH = 200;

    private final UUID id;
    private final String type;
    private final String title;
    private final CapabilityId capabilityId;
    private final NetworkId capabilityNetworkId;
    private final String capabilityTypeCode;
    private final AgreementParty proposer;
    private final AgreementParty counterparty;
    private AgreementStatus status;
    private final List<AgreementVersion> versions;
    private final Instant createdAt;
    private Instant activatedAt;
    private Instant closedAt;
    private String closingReason;
    private final Long lockVersion;

    private Agreement(UUID id, String type, String title, CapabilityId capabilityId, NetworkId capabilityNetworkId,
                      String capabilityTypeCode, AgreementParty proposer, AgreementParty counterparty,
                      AgreementStatus status, List<AgreementVersion> versions, Instant createdAt, Instant activatedAt,
                      Instant closedAt, String closingReason, Long lockVersion) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.capabilityId = Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        this.capabilityNetworkId = Objects.requireNonNull(capabilityNetworkId, "capabilityNetworkId must not be null");
        this.capabilityTypeCode = Objects.requireNonNull(capabilityTypeCode, "capabilityTypeCode must not be null");
        this.proposer = Objects.requireNonNull(proposer, "proposer must not be null");
        this.counterparty = Objects.requireNonNull(counterparty, "counterparty must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.versions = new ArrayList<>(versions);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.activatedAt = activatedAt;
        this.closedAt = closedAt;
        this.closingReason = closingReason;
        this.lockVersion = lockVersion;
    }

    public static Agreement draft(UUID id, String type, String title, CapabilityId capabilityId,
                                  NetworkId capabilityNetworkId, String capabilityTypeCode, AgreementParty proposer,
                                  AgreementParty counterparty, Map<String, Object> terms, Actor actor, Instant now) {
        if (proposer.equals(counterparty)) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "AGREEMENT_PARTIES_IDENTICAL",
                    "An agreement needs two different parties");
        }
        String normalizedTitle = title == null ? "" : title.trim();
        if (normalizedTitle.isEmpty() || normalizedTitle.length() > TITLE_MAX_LENGTH) {
            throw new ValidationException("INVALID_AGREEMENT_TITLE",
                    "The title must have between 1 and " + TITLE_MAX_LENGTH + " characters");
        }
        Map<String, Object> initial = terms == null ? Map.of() : terms;
        AgreementVersion first = version(1, initial, changes(Map.of(), initial), proposer, actor, now);
        Agreement agreement = new Agreement(id, type == null || type.isBlank() ? "CAPABILITY_USAGE" : type.trim(),
                normalizedTitle, capabilityId, capabilityNetworkId, capabilityTypeCode, proposer, counterparty,
                AgreementStatus.DRAFT, List.of(first), now, null, null, null, null);
        agreement.record("CREATED", actor, now);
        return agreement;
    }

    public static Agreement restore(UUID id, String type, String title, CapabilityId capabilityId,
                                    NetworkId capabilityNetworkId, String capabilityTypeCode, AgreementParty proposer,
                                    AgreementParty counterparty, AgreementStatus status,
                                    List<AgreementVersion> versions, Instant createdAt, Instant activatedAt,
                                    Instant closedAt, String closingReason, Long lockVersion) {
        return new Agreement(id, type, title, capabilityId, capabilityNetworkId, capabilityTypeCode, proposer,
                counterparty, status, versions, createdAt, activatedAt, closedAt, closingReason, lockVersion);
    }

    public void propose(Integer expectedVersion, Actor actor, Instant now) {
        requireStatus("propose", AgreementStatus.DRAFT);
        requireCurrent(expectedVersion);
        if (!current().onBehalfOf().represents(actor)) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "AGREEMENT_PARTY_REQUIRED",
                    "Only the party that drafted the agreement can propose it");
        }
        status = AgreementStatus.PROPOSED;
        record("PROPOSED", actor, now);
    }

    public void revise(Map<String, Object> terms, Integer expectedVersion, Actor actor, Instant now) {
        requireStatus("revise", AgreementStatus.DRAFT, AgreementStatus.PROPOSED);
        if (expectedVersion == null) {
            throw new ValidationException("EXPECTED_VERSION_REQUIRED", "A revision must name the version it revises");
        }
        requireCurrent(expectedVersion);
        AgreementParty party = partyOf(actor);
        AgreementVersion previous = current();
        Map<String, Object> revised = terms == null ? Map.of() : terms;
        previous.supersede();
        versions.add(version(previous.number() + 1, revised, changes(previous.terms(), revised), party, actor, now));
        record("REVISED", actor, now);
    }

    public void accept(int version, Actor actor, Instant now) {
        requireStatus("accept", AgreementStatus.PROPOSED);
        requireAcceptable(version, actor);
        current().accept(actor.principalId(), actor.decisionId(), now);
        status = AgreementStatus.ACCEPTED;
        record("ACCEPTED", actor, now);
    }

    public void reject(int version, String reason, Actor actor, Instant now) {
        requireStatus("reject", AgreementStatus.PROPOSED);
        requireAcceptable(version, actor);
        status = AgreementStatus.REJECTED;
        close(reason, now);
        record("REJECTED", actor, now);
    }

    public void activate(Actor actor, Instant now) {
        requireStatus("activate", AgreementStatus.ACCEPTED);
        partyOf(actor);
        status = AgreementStatus.ACTIVE;
        activatedAt = now;
        record("ACTIVATED", actor, now);
    }

    public void complete(Actor actor, Instant now) {
        requireStatus("complete", AgreementStatus.ACTIVE);
        partyOf(actor);
        status = AgreementStatus.COMPLETED;
        close(null, now);
        record("COMPLETED", actor, now);
    }

    public void terminate(String reason, Actor actor, Instant now) {
        requireStatus("terminate", AgreementStatus.DRAFT, AgreementStatus.PROPOSED, AgreementStatus.ACCEPTED,
                AgreementStatus.ACTIVE);
        partyOf(actor);
        status = AgreementStatus.TERMINATED;
        close(reason, now);
        record("TERMINATED", actor, now);
    }

    public AgreementParty partyOf(Actor actor) {
        if (proposer.represents(actor)) {
            return proposer;
        }
        if (counterparty.represents(actor)) {
            return counterparty;
        }
        throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "AGREEMENT_PARTY_REQUIRED",
                "The principal does not act for a party of agreement " + id);
    }

    public boolean isParty(Actor actor) {
        return proposer.represents(actor) || counterparty.represents(actor);
    }

    public boolean involves(NetworkId networkId) {
        return proposer.networkId().equals(networkId) || counterparty.networkId().equals(networkId);
    }

    public NetworkId counterpartNetworkOf(NetworkId networkId) {
        return proposer.networkId().equals(networkId) ? counterparty.networkId() : proposer.networkId();
    }

    public AgreementVersion current() {
        return versions.getLast();
    }

    public Optional<AgreementVersion> version(int number) {
        return versions.stream().filter(version -> version.number() == number).findFirst();
    }

    private void requireAcceptable(int number, Actor actor) {
        AgreementVersion requested = version(number).orElseThrow(() -> new NotFoundException("AgreementVersion", number));
        if (requested.superseded()) {
            throw new ConflictException("AGREEMENT_VERSION_SUPERSEDED",
                    "Version " + number + " of agreement " + id + " has been superseded by version " + current().number());
        }
        AgreementParty proposing = requested.onBehalfOf();
        AgreementParty other = proposing.equals(proposer) ? counterparty : proposer;
        if (!other.represents(actor)) {
            String code = proposing.represents(actor) ? "AGREEMENT_SELF_ACCEPTANCE" : "AGREEMENT_PARTY_REQUIRED";
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, code,
                    "Only the other party can accept or reject version " + number);
        }
    }

    private void requireCurrent(Integer expectedVersion) {
        if (expectedVersion != null && expectedVersion != current().number()) {
            throw new ConflictException("AGREEMENT_VERSION_CONFLICT",
                    "Agreement " + id + " is at version " + current().number() + ", not " + expectedVersion);
        }
    }

    private void requireStatus(String transition, AgreementStatus... allowed) {
        if (!Set.of(allowed).contains(status)) {
            throw new ConflictException("AGREEMENT_INVALID_TRANSITION",
                    "Cannot " + transition + " agreement " + id + " in status " + status);
        }
    }

    private void close(String reason, Instant now) {
        closedAt = now;
        closingReason = reason == null || reason.isBlank() ? null : reason.trim();
    }

    private void record(String change, Actor actor, Instant now) {
        registerEvent(new AgreementChanged(now, id, proposer.networkId(), change, status.name(), current().number(),
                actor.principalId(), actor.networkId()));
    }

    private static AgreementVersion version(int number, Map<String, Object> terms, List<String> changes,
                                            AgreementParty party, Actor actor, Instant now) {
        return new AgreementVersion(UUID.randomUUID(), number, terms, changes, party, actor.principalId(),
                actor.identityId(), actor.delegationId(), actor.decisionId(), actor.federationId(), now, null, null,
                null, false);
    }

    static List<String> changes(Map<String, Object> previous, Map<String, Object> next) {
        Set<String> keys = new TreeSet<>(previous.keySet());
        keys.addAll(next.keySet());
        return keys.stream().filter(key -> !Objects.equals(previous.get(key), next.get(key))).toList();
    }

    @Override
    public UUID id() {
        return id;
    }

    public NetworkId networkId() {
        return proposer.networkId();
    }

    public String type() {
        return type;
    }

    public String title() {
        return title;
    }

    public CapabilityId capabilityId() {
        return capabilityId;
    }

    public NetworkId capabilityNetworkId() {
        return capabilityNetworkId;
    }

    public String capabilityTypeCode() {
        return capabilityTypeCode;
    }

    public AgreementParty proposer() {
        return proposer;
    }

    public AgreementParty counterparty() {
        return counterparty;
    }

    public AgreementStatus status() {
        return status;
    }

    public List<AgreementVersion> versions() {
        return Collections.unmodifiableList(versions);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> activatedAt() {
        return Optional.ofNullable(activatedAt);
    }

    public Optional<Instant> closedAt() {
        return Optional.ofNullable(closedAt);
    }

    public Optional<String> closingReason() {
        return Optional.ofNullable(closingReason);
    }

    public Long lockVersion() {
        return lockVersion;
    }
}
