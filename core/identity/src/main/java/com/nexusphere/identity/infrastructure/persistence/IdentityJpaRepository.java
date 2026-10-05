package com.nexusphere.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface IdentityJpaRepository extends JpaRepository<IdentityEntity, UUID> {
}
