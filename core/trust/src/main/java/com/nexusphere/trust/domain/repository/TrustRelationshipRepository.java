package com.nexusphere.trust.domain.repository;

import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.trust.domain.model.Party;
import com.nexusphere.trust.domain.model.TrustRelationship;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrustRelationshipRepository {

    TrustRelationship save(TrustRelationship trust);

    Optional<TrustRelationship> findById(UUID id);

    List<TrustRelationship> findInvolving(NetworkId networkId);

    List<TrustRelationship> findActive(Party source, Party target);
}
