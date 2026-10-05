package com.nexusphere.identity.infrastructure.persistence;

import com.nexusphere.identity.domain.model.Credential;
import com.nexusphere.identity.domain.repository.CredentialRepository;
import com.nexusphere.shared.id.IdentityId;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaCredentialRepository implements CredentialRepository {

    private final CredentialJpaRepository jpa;

    JpaCredentialRepository(CredentialJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Credential save(Credential credential) {
        CredentialEntity saved = jpa.saveAndFlush(new CredentialEntity(credential.id(), credential.identityId().value(),
                credential.secretHash(), credential.createdAt(), credential.expiresAt(), credential.revokedAt()));
        return toDomain(saved);
    }

    @Override
    public Optional<Credential> findById(IdentityId identityId, UUID id) {
        return jpa.findByIdAndIdentityId(id, identityId.value()).map(JpaCredentialRepository::toDomain);
    }

    @Override
    public List<Credential> findByIdentity(IdentityId identityId) {
        return jpa.findByIdentityIdOrderByCreatedAtAsc(identityId.value()).stream()
                .map(JpaCredentialRepository::toDomain).toList();
    }

    private static Credential toDomain(CredentialEntity entity) {
        return new Credential(entity.getId(), new IdentityId(entity.getIdentityId()), entity.getSecretHash(),
                entity.getCreatedAt(), entity.getExpiresAt(), entity.getRevokedAt());
    }
}
