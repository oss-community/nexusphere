package com.nexusphere.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CredentialJpaRepository extends JpaRepository<CredentialEntity, UUID> {

    List<CredentialEntity> findByIdentityIdOrderByCreatedAtAsc(UUID identityId);

    Optional<CredentialEntity> findByIdAndIdentityId(UUID id, UUID identityId);
}
