package com.nexusphere.identity.infrastructure.persistence;

import com.nexusphere.identity.domain.model.Credential;
import com.nexusphere.identity.domain.repository.CredentialRepository;
import com.nexusphere.shared.id.IdentityId;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
class JpaCredentialRepository implements CredentialRepository {

    private final CredentialJpaRepository jpa;

    JpaCredentialRepository(CredentialJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Credential save(Credential credential) {
        CredentialEntity saved = jpa.saveAndFlush(new CredentialEntity(credential.id(), credential.identityId().value(),
                credential.secretHash(), credential.createdAt()));
        return toDomain(saved);
    }

    @Override
    public List<Credential> findByIdentity(IdentityId identityId) {
        return jpa.findByIdentityId(identityId.value()).stream().map(JpaCredentialRepository::toDomain).toList();
    }

    private static Credential toDomain(CredentialEntity entity) {
        return new Credential(entity.getId(), new IdentityId(entity.getIdentityId()), entity.getSecretHash(),
                entity.getCreatedAt());
    }
}
