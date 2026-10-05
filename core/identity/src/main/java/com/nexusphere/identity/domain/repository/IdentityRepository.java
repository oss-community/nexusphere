package com.nexusphere.identity.domain.repository;

import com.nexusphere.identity.domain.model.Identity;
import com.nexusphere.shared.id.IdentityId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IdentityRepository {

    Identity save(Identity identity);

    Optional<Identity> findById(IdentityId id);

    List<Identity> findAll(Collection<IdentityId> ids);
}
