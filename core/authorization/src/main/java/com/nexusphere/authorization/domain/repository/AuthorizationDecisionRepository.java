package com.nexusphere.authorization.domain.repository;

import com.nexusphere.authorization.contract.AuthorizationDecision;

import java.util.Optional;
import java.util.UUID;

public interface AuthorizationDecisionRepository {

    AuthorizationDecision save(AuthorizationDecision decision);

    Optional<AuthorizationDecision> findById(UUID id);
}
