package com.nexusphere.identity.contract;

import com.nexusphere.shared.id.IdentityId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IdentityDirectory {

    Optional<IdentitySnapshot> find(IdentityId id);

    List<IdentitySnapshot> findAll(Collection<IdentityId> ids);
}
