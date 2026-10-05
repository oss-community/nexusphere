package com.nexusphere.organization.domain.repository;

import com.nexusphere.organization.domain.model.Organization;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

import java.util.List;
import java.util.Optional;

public interface OrganizationRepository {

    Organization save(Organization organization);

    Optional<Organization> findById(NetworkId networkId, OrganizationId id);

    List<Organization> findAll(NetworkId networkId);

    boolean existsByName(NetworkId networkId, String name);
}
