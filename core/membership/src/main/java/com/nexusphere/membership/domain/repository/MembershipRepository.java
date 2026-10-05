package com.nexusphere.membership.domain.repository;

import com.nexusphere.membership.domain.model.Membership;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository {

    Membership save(Membership membership);

    Optional<Membership> findById(NetworkId networkId, UUID id);

    Optional<Membership> findActive(IdentityId identityId, NetworkId networkId);

    List<Membership> findAll(NetworkId networkId);

    List<Membership> findActive(NetworkId networkId);
}
