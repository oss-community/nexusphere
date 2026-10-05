package com.nexusphere.authorization.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface AuthorizationDecisionJpaRepository extends JpaRepository<AuthorizationDecisionEntity, UUID> {
}
