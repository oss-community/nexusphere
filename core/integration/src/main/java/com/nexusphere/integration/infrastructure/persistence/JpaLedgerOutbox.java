package com.nexusphere.integration.infrastructure.persistence;

import com.nexusphere.integration.domain.model.LedgerGrant;
import com.nexusphere.integration.domain.model.OutboxMessage;
import com.nexusphere.integration.domain.repository.LedgerOutbox;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaLedgerOutbox implements LedgerOutbox {

    private static final long LOCK_KEY = 0x4e5850484c444752L;
    private static final int MAX_ERROR = 500;

    private final OutboxJpaRepository messages;
    private final LedgerGrantJpaRepository grants;
    private final EntityManager entityManager;

    JpaLedgerOutbox(OutboxJpaRepository messages, LedgerGrantJpaRepository grants, EntityManager entityManager) {
        this.messages = messages;
        this.grants = grants;
        this.entityManager = entityManager;
    }

    @Override
    public void append(OutboxMessage message) {
        messages.save(new OutboxEntity(message.id(), message.kind(), message.payload(), message.createdAt()));
    }

    @Override
    public boolean lock() {
        Object locked = entityManager.createNativeQuery("select pg_try_advisory_xact_lock(?1)")
                .setParameter(1, LOCK_KEY).getSingleResult();
        return Boolean.TRUE.equals(locked);
    }

    @Override
    public List<OutboxMessage> pending(int limit) {
        return messages.findBySentAtIsNullAndRejectedAtIsNullOrderBySeqAsc(Limit.of(limit)).stream()
                .map(e -> new OutboxMessage(e.getId(), e.getKind(), e.getPayload(), e.getCreatedAt(),
                        e.getAttempts(), e.getLastError()))
                .toList();
    }

    @Override
    public void markSent(UUID id, Instant sentAt) {
        messages.findById(id).ifPresent(e -> e.sent(sentAt));
    }

    @Override
    public void markFailed(UUID id, String error) {
        messages.findById(id).ifPresent(e -> e.failed(truncate(error)));
    }

    @Override
    public void markRejected(UUID id, String error, Instant rejectedAt) {
        messages.findById(id).ifPresent(e -> e.rejected(truncate(error), rejectedAt));
    }

    @Override
    public long countPending() {
        return messages.countBySentAtIsNullAndRejectedAtIsNull();
    }

    @Override
    public void saveGrant(LedgerGrant grant) {
        grants.saveAndFlush(new LedgerGrantEntity(grant.grantId(), grant.delegationId(), grant.agentId(),
                grant.createdAt(), grant.revokedAt()));
    }

    @Override
    public Optional<LedgerGrant> activeGrant(UUID delegationId) {
        return grants.findByDelegationIdAndRevokedAtIsNull(delegationId)
                .map(e -> new LedgerGrant(e.getDelegationId(), e.getGrantId(), e.getAgentId(), e.getCreatedAt(),
                        e.getRevokedAt()));
    }

    @Override
    public void markGrantRevoked(String grantId, Instant revokedAt) {
        grants.findById(grantId).ifPresent(e -> e.revoked(revokedAt));
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR);
    }
}
