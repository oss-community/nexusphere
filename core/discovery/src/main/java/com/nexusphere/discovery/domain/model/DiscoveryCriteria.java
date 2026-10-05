package com.nexusphere.discovery.domain.model;

import com.nexusphere.capability.contract.CapabilitySnapshot;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.OrganizationId;

import java.util.Locale;
import java.util.Set;

public record DiscoveryCriteria(String typeCode, String ownerType, OrganizationId organizationId) {

    private static final Set<String> OWNER_TYPES = Set.of("ORGANIZATION", "AGENT", "MACHINE", "SERVICE",
            "APPLICATION");

    public DiscoveryCriteria {
        if (ownerType != null) {
            ownerType = ownerType.trim().toUpperCase(Locale.ROOT);
            if (!OWNER_TYPES.contains(ownerType)) {
                throw new ValidationException("INVALID_OWNER_TYPE", "The owner type must be one of " + OWNER_TYPES);
            }
        }
    }

    public boolean admits(CapabilitySnapshot capability, boolean federated) {
        boolean visible = federated ? capability.available() && "FEDERATED".equals(capability.visibility())
                : capability.discoverable();
        return visible
                && (typeCode == null || typeCode.equals(capability.typeCode()))
                && (ownerType == null || ownerType.equals(capability.ownerType()))
                && (organizationId == null || organizationId.equals(capability.accountableOrganizationId()));
    }
}
