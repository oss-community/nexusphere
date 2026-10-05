package com.nexusphere.membership.infrastructure.persistence;

import com.nexusphere.membership.domain.model.Membership;
import com.nexusphere.membership.domain.model.MembershipStatus;
import com.nexusphere.membership.domain.repository.MembershipRepository;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaMembershipRepository implements MembershipRepository {

    private final MembershipJpaRepository jpa;

    JpaMembershipRepository(MembershipJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Membership save(Membership membership) {
        try {
            return toDomain(jpa.saveAndFlush(toEntity(membership)));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("MEMBERSHIP_ALREADY_ACTIVE", "Identity " + membership.identityId()
                    + " already has an active membership in network " + membership.networkId());
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("CONCURRENT_MODIFICATION", "Membership " + membership.id() + " was changed concurrently");
        }
    }

    @Override
    public Optional<Membership> findById(NetworkId networkId, UUID id) {
        return jpa.findByIdAndNetworkId(id, networkId.value()).map(JpaMembershipRepository::toDomain);
    }

    @Override
    public Optional<Membership> findActive(IdentityId identityId, NetworkId networkId) {
        return jpa.findByIdentityIdAndNetworkIdAndStatus(identityId.value(), networkId.value(), MembershipStatus.ACTIVE)
                .map(JpaMembershipRepository::toDomain);
    }

    @Override
    public List<Membership> findAll(NetworkId networkId) {
        return jpa.findByNetworkIdOrderByJoinedAtAscIdAsc(networkId.value()).stream()
                .map(JpaMembershipRepository::toDomain).toList();
    }

    @Override
    public List<Membership> findActive(IdentityId identityId) {
        return jpa.findByIdentityIdAndStatus(identityId.value(), MembershipStatus.ACTIVE).stream()
                .map(JpaMembershipRepository::toDomain).toList();
    }

    @Override
    public List<Membership> findActive(NetworkId networkId) {
        return jpa.findByNetworkIdAndStatusOrderByJoinedAtAscIdAsc(networkId.value(), MembershipStatus.ACTIVE).stream()
                .map(JpaMembershipRepository::toDomain).toList();
    }

    private static MembershipEntity toEntity(Membership membership) {
        return new MembershipEntity(membership.id(), membership.identityId().value(), membership.networkId().value(),
                membership.organizationId().map(OrganizationId::value).orElse(null), membership.role(), membership.status(),
                membership.joinedAt(), membership.terminatedAt().orElse(null), membership.version());
    }

    private static Membership toDomain(MembershipEntity entity) {
        return Membership.restore(entity.getId(), new IdentityId(entity.getIdentityId()),
                new NetworkId(entity.getNetworkId()),
                entity.getOrganizationId() == null ? null : new OrganizationId(entity.getOrganizationId()),
                entity.getRole(), entity.getStatus(), entity.getJoinedAt(), entity.getTerminatedAt(), entity.getVersion());
    }
}
