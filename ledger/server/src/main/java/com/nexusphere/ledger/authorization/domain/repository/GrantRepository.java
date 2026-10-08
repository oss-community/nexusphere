package com.nexusphere.ledger.authorization.domain.repository;

import com.nexusphere.ledger.authorization.domain.model.Grant;
import com.nexusphere.ledger.authorization.domain.model.GrantQuery;
import com.nexusphere.ledger.chain.GrantTerms;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GrantRepository {

    void insert(GrantTerms terms, String reason);

    Optional<Grant> find(UUID id);

    Optional<Grant> lock(UUID id);

    List<Grant> lockActive(String agentId, String principalId);

    List<Grant> find(GrantQuery query);

    void use(UUID id);

    void release(UUID id);

    void revoke(UUID id, Instant revokedAt, String reason);
}
