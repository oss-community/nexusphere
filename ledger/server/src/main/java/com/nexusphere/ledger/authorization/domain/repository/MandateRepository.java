package com.nexusphere.ledger.authorization.domain.repository;

import com.nexusphere.ledger.authorization.domain.model.Mandate;

import java.time.Instant;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MandateRepository {

    long reserve(UUID id, UUID grantId, String agentId, String principalId, String audience, Instant issuedAt,
                 Instant expiresAt);

    void attachToken(UUID id, String token);

    Optional<Mandate> find(UUID id);

    Optional<Mandate> lock(UUID id);

    List<Mandate> lockActiveForGrant(UUID grantId);

    List<Mandate> findByGrant(UUID grantId);

    void revoke(UUID id, Instant revokedAt, String reason);

    BitSet revokedIndexes();
}
