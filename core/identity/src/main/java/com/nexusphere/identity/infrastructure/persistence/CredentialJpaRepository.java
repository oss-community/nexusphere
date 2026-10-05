package com.nexusphere.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface CredentialJpaRepository extends JpaRepository<CredentialEntity, UUID> {

    List<CredentialEntity> findByIdentityId(UUID identityId);
}
