package com.nexusphere.agreement.domain.repository;

import com.nexusphere.agreement.domain.model.Agreement;
import com.nexusphere.shared.id.NetworkId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgreementRepository {

    Agreement save(Agreement agreement);

    Optional<Agreement> findById(UUID id);

    List<Agreement> findInvolving(NetworkId networkId);
}
