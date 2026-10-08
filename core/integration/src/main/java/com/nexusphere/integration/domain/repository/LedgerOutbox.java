package com.nexusphere.integration.domain.repository;

import com.nexusphere.integration.domain.model.LedgerGrant;
import com.nexusphere.integration.domain.model.OutboxMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerOutbox {

    void append(OutboxMessage message);

    boolean lock();

    List<OutboxMessage> pending(int limit);

    void markSent(UUID id, Instant sentAt);

    void markFailed(UUID id, String error);

    void markRejected(UUID id, String error, Instant rejectedAt);

    long countPending();

    void saveGrant(LedgerGrant grant);

    Optional<LedgerGrant> activeGrant(UUID delegationId);

    void markGrantRevoked(String grantId, Instant revokedAt);
}
