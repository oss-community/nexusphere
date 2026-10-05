package com.nexusphere.trust.infrastructure.persistence;

import com.nexusphere.trust.domain.model.PartyType;
import com.nexusphere.trust.domain.model.TrustStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface TrustRelationshipJpaRepository extends JpaRepository<TrustRelationshipEntity, UUID> {

    @Query("""
            select t from TrustRelationshipEntity t
            where t.sourceNetworkId = :networkId or t.targetNetworkId = :networkId
            order by t.createdAt, t.id
            """)
    List<TrustRelationshipEntity> findInvolving(UUID networkId);

    List<TrustRelationshipEntity> findBySourceTypeAndSourceIdAndSourceNetworkIdAndTargetTypeAndTargetIdAndTargetNetworkIdAndStatus(
            PartyType sourceType, UUID sourceId, UUID sourceNetworkId, PartyType targetType, UUID targetId,
            UUID targetNetworkId, TrustStatus status);
}
