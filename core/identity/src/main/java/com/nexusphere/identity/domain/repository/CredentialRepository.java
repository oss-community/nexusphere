package com.nexusphere.identity.domain.repository;

import com.nexusphere.identity.domain.model.Credential;
import com.nexusphere.shared.id.IdentityId;

import java.util.List;

public interface CredentialRepository {

    Credential save(Credential credential);

    List<Credential> findByIdentity(IdentityId identityId);
}
