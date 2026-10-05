package com.nexusphere.identity.domain.repository;

import com.nexusphere.identity.domain.model.Credential;
import com.nexusphere.shared.id.IdentityId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository {

    Credential save(Credential credential);

    Optional<Credential> findById(IdentityId identityId, UUID id);

    List<Credential> findByIdentity(IdentityId identityId);
}
