package com.nexusphere.delegation.domain.model;

import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.NetworkId;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public record DelegationConstraints(Set<String> capabilityTypes, Set<NetworkId> networks, Set<String> resourceTypes) {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_.-]*");

    public DelegationConstraints {
        capabilityTypes = Collections.unmodifiableSet(new TreeSet<>(capabilityTypes == null ? Set.of() : capabilityTypes));
        networks = networks == null ? Set.of() : Set.copyOf(networks);
        resourceTypes = Collections.unmodifiableSet(new TreeSet<>(resourceTypes == null ? Set.of() : resourceTypes));
    }

    public static DelegationConstraints none() {
        return new DelegationConstraints(Set.of(), Set.of(), Set.of());
    }

    public static DelegationConstraints of(Collection<String> capabilityTypes, Collection<NetworkId> networks,
                                           Collection<String> resourceTypes) {
        return new DelegationConstraints(codes(capabilityTypes, "capability type"),
                networks == null ? Set.of() : Set.copyOf(networks), codes(resourceTypes, "resource type"));
    }

    private static Set<String> codes(Collection<String> values, String label) {
        Set<String> codes = new TreeSet<>();
        if (values == null) {
            return codes;
        }
        for (String value : values) {
            String code = value == null ? "" : value.trim();
            if (!CODE.matcher(code).matches()) {
                throw new ValidationException("INVALID_DELEGATION_CONSTRAINT",
                        "The " + label + " constraint " + value + " is not a valid code");
            }
            codes.add(code);
        }
        return codes;
    }
}
