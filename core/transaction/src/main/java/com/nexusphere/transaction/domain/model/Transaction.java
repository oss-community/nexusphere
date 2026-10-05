package com.nexusphere.transaction.domain.model;

import com.nexusphere.shared.domain.AggregateRoot;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;
import com.nexusphere.transaction.contract.TransactionChanged;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class Transaction extends AggregateRoot<UUID> {

    public record AgreementCoverage(UUID id, int version, boolean active, CapabilityId capabilityId,
                            NetworkId capabilityNetworkId, TransactionParty requester, TransactionParty provider) {
    }

    private final UUID id;
    private final String type;
    private final UUID agreementId;
    private final int agreementVersion;
    private final CapabilityId capabilityId;
    private final NetworkId capabilityNetworkId;
    private final TransactionParty requester;
    private final TransactionParty provider;
    private final PrincipalId initiatingPrincipalId;
    private final IdentityId initiatingIdentityId;
    private final Authority authority;
    private final Map<String, Object> metadata;
    private TransactionStatus status;
    private String reason;
    private Map<String, Object> result;
    private PrincipalId executorPrincipalId;
    private IdentityId executorIdentityId;
    private UUID executionDecisionId;
    private final Instant createdAt;
    private Instant authorizedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private final Long lockVersion;

    private Transaction(UUID id, String type, UUID agreementId, int agreementVersion, CapabilityId capabilityId,
                        NetworkId capabilityNetworkId, TransactionParty requester, TransactionParty provider,
                        PrincipalId initiatingPrincipalId, IdentityId initiatingIdentityId, Authority authority,
                        Map<String, Object> metadata, TransactionStatus status, String reason,
                        Map<String, Object> result, PrincipalId executorPrincipalId, IdentityId executorIdentityId,
                        UUID executionDecisionId, Instant createdAt, Instant authorizedAt, Instant startedAt,
                        Instant finishedAt, Long lockVersion) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.agreementId = Objects.requireNonNull(agreementId, "agreementId must not be null");
        this.agreementVersion = agreementVersion;
        this.capabilityId = Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        this.capabilityNetworkId = Objects.requireNonNull(capabilityNetworkId, "capabilityNetworkId must not be null");
        this.requester = Objects.requireNonNull(requester, "requester must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.initiatingPrincipalId = Objects.requireNonNull(initiatingPrincipalId, "initiator must not be null");
        this.initiatingIdentityId = Objects.requireNonNull(initiatingIdentityId, "initiator identity must not be null");
        this.authority = Objects.requireNonNull(authority, "authority must not be null");
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.reason = reason;
        this.result = result == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(result));
        this.executorPrincipalId = executorPrincipalId;
        this.executorIdentityId = executorIdentityId;
        this.executionDecisionId = executionDecisionId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.authorizedAt = authorizedAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.lockVersion = lockVersion;
    }

    public static Transaction request(UUID id, String type, AgreementCoverage agreement, CapabilityId requestedCapability,
                                      Participant initiator, Authority authority, Map<String, Object> metadata,
                                      Instant now) {
        Transaction transaction = new Transaction(id, type == null || type.isBlank() ? "CAPABILITY_INVOCATION"
                : type.trim(), agreement.id(), agreement.version(), agreement.capabilityId(),
                agreement.capabilityNetworkId(), agreement.requester(), agreement.provider(),
                initiator.principalId(), initiator.identityId(), authority, metadata == null ? Map.of() : metadata,
                TransactionStatus.REQUESTED, null, null, null, null, null, now, null, null, null, null);
        transaction.record("REQUESTED", initiator, now);
        if (!agreement.active()) {
            transaction.reject("AGREEMENT_NOT_ACTIVE", initiator, now);
        } else if (requestedCapability != null && !requestedCapability.equals(agreement.capabilityId())) {
            transaction.reject("CAPABILITY_NOT_COVERED", initiator, now);
        } else {
            transaction.status = TransactionStatus.AUTHORIZED;
            transaction.authorizedAt = now;
            transaction.record("AUTHORIZED", initiator, now);
        }
        return transaction;
    }

    public static Transaction restore(UUID id, String type, UUID agreementId, int agreementVersion,
                                      CapabilityId capabilityId, NetworkId capabilityNetworkId,
                                      TransactionParty requester, TransactionParty provider,
                                      PrincipalId initiatingPrincipalId, IdentityId initiatingIdentityId,
                                      Authority authority, Map<String, Object> metadata, TransactionStatus status,
                                      String reason, Map<String, Object> result, PrincipalId executorPrincipalId,
                                      IdentityId executorIdentityId, UUID executionDecisionId, Instant createdAt,
                                      Instant authorizedAt, Instant startedAt, Instant finishedAt,
                                      Long lockVersion) {
        return new Transaction(id, type, agreementId, agreementVersion, capabilityId, capabilityNetworkId, requester,
                provider, initiatingPrincipalId, initiatingIdentityId, authority, metadata, status, reason, result,
                executorPrincipalId, executorIdentityId, executionDecisionId, createdAt, authorizedAt, startedAt,
                finishedAt, lockVersion);
    }

    public void execute(Participant executor, UUID decisionId, Instant now) {
        requireStatus("execute", TransactionStatus.AUTHORIZED);
        status = TransactionStatus.EXECUTING;
        executorPrincipalId = executor.principalId();
        executorIdentityId = executor.identityId();
        executionDecisionId = decisionId;
        startedAt = now;
        record("EXECUTING", executor, now);
    }

    public void complete(Map<String, Object> outcome, Participant executor, Instant now) {
        requireStatus("complete", TransactionStatus.EXECUTING);
        status = TransactionStatus.COMPLETED;
        result = outcome == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(outcome));
        finishedAt = now;
        record("COMPLETED", executor, now);
    }

    public void fail(String failure, Participant executor, Instant now) {
        requireStatus("fail", TransactionStatus.AUTHORIZED, TransactionStatus.EXECUTING);
        status = TransactionStatus.FAILED;
        reason = failure == null || failure.isBlank() ? "EXECUTION_FAILED" : failure.trim();
        finishedAt = now;
        record("FAILED", executor, now);
    }

    public void cancel(Participant participant, Instant now) {
        requireStatus("cancel", TransactionStatus.REQUESTED, TransactionStatus.AUTHORIZED);
        status = TransactionStatus.CANCELLED;
        finishedAt = now;
        record("CANCELLED", participant, now);
    }

    public void requireProvider(Participant participant, IdentityId capabilityOwnerIdentity) {
        boolean owner = participant.networkId().equals(provider.networkId())
                && participant.identityId().equals(capabilityOwnerIdentity);
        if (!owner && !provider.represents(participant)) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "TRANSACTION_PROVIDER_REQUIRED",
                    "Only the providing party can execute, complete or fail transaction " + id);
        }
    }

    public void requireRequester(Participant participant) {
        if (!requester.represents(participant)) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "TRANSACTION_REQUESTER_REQUIRED",
                    "Only the requesting party can cancel transaction " + id);
        }
    }

    public boolean involves(NetworkId networkId) {
        return requester.networkId().equals(networkId) || provider.networkId().equals(networkId);
    }

    public boolean isParty(Participant participant) {
        return requester.represents(participant) || provider.represents(participant);
    }

    private void reject(String rejection, Participant participant, Instant now) {
        status = TransactionStatus.REJECTED;
        reason = rejection;
        finishedAt = now;
        record("REJECTED", participant, now);
    }

    private void requireStatus(String transition, TransactionStatus... allowed) {
        if (!Set.of(allowed).contains(status)) {
            throw new ConflictException("TRANSACTION_INVALID_TRANSITION",
                    "Cannot " + transition + " transaction " + id + " in status " + status);
        }
    }

    private void record(String change, Participant participant, Instant now) {
        registerEvent(new TransactionChanged(now, id, requester.networkId(), provider.networkId(), agreementId, change,
                status.name(), reason, participant.principalId(), participant.networkId()));
    }

    @Override
    public UUID id() {
        return id;
    }

    public NetworkId networkId() {
        return requester.networkId();
    }

    public String type() {
        return type;
    }

    public UUID agreementId() {
        return agreementId;
    }

    public int agreementVersion() {
        return agreementVersion;
    }

    public CapabilityId capabilityId() {
        return capabilityId;
    }

    public NetworkId capabilityNetworkId() {
        return capabilityNetworkId;
    }

    public TransactionParty requester() {
        return requester;
    }

    public TransactionParty provider() {
        return provider;
    }

    public PrincipalId initiatingPrincipalId() {
        return initiatingPrincipalId;
    }

    public IdentityId initiatingIdentityId() {
        return initiatingIdentityId;
    }

    public Authority authority() {
        return authority;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public TransactionStatus status() {
        return status;
    }

    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    public Optional<Map<String, Object>> result() {
        return Optional.ofNullable(result);
    }

    public Optional<PrincipalId> executorPrincipalId() {
        return Optional.ofNullable(executorPrincipalId);
    }

    public Optional<IdentityId> executorIdentityId() {
        return Optional.ofNullable(executorIdentityId);
    }

    public Optional<UUID> executionDecisionId() {
        return Optional.ofNullable(executionDecisionId);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> authorizedAt() {
        return Optional.ofNullable(authorizedAt);
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> finishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    public Long lockVersion() {
        return lockVersion;
    }
}
