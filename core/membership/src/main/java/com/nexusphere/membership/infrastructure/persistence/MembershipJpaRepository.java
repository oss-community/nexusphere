package com.nexusphere.membership.infrastructure.persistence;

import com.nexusphere.membership.domain.model.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MembershipJpaRepository extends JpaRepository<MembershipEntity, UUID> {

    Optional<MembershipEntity> findByIdAndNetworkId(UUID id, UUID networkId);

    Optional<MembershipEntity> findByIdentityIdAndNetworkIdAndStatus(UUID identityId, UUID networkId,
                                                                     MembershipStatus status);

    List<MembershipEntity> findByNetworkIdOrderByJoinedAtAscIdAsc(UUID networkId);

    List<MembershipEntity> findByNetworkIdAndStatusOrderByJoinedAtAscIdAsc(UUID networkId, MembershipStatus status);
}
