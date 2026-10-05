package com.nexusphere.authorization.domain.model;

import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.shared.error.ValidationException;

import java.util.Arrays;
import java.util.Set;

public enum Role {
    MEMBER(Set.of(Actions.CAPABILITY_DISCOVER, Actions.IDENTITY_READ)),
    CAPABILITY_MANAGER(Set.of(Actions.CAPABILITY_DISCOVER, Actions.CAPABILITY_REGISTER, Actions.CAPABILITY_PUBLISH)),
    AGREEMENT_MANAGER(Set.of(Actions.CAPABILITY_DISCOVER, Actions.AGREEMENT_PROPOSE, Actions.AGREEMENT_ACCEPT,
            Actions.AGREEMENT_MANAGE, Actions.DELEGATION_GRANT)),
    TRANSACTION_OPERATOR(Set.of(Actions.CAPABILITY_DISCOVER, Actions.CAPABILITY_INVOKE, Actions.TRANSACTION_INITIATE,
            Actions.TRANSACTION_EXECUTE, Actions.DELEGATION_GRANT)),
    FEDERATION_MANAGER(Set.of(Actions.TRUST_MANAGE, Actions.FEDERATION_MANAGE)),
    AUDITOR(Set.of(Actions.AUDIT_READ)),
    NETWORK_ADMINISTRATOR(Actions.ALL);

    private final Set<String> actions;

    Role(Set<String> actions) {
        this.actions = Set.copyOf(actions);
    }

    public Set<String> actions() {
        return actions;
    }

    public boolean grants(String action) {
        return actions.contains(action);
    }

    public static Role parse(String value) {
        return Arrays.stream(values()).filter(role -> role.name().equalsIgnoreCase(value == null ? "" : value.trim()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("INVALID_ROLE",
                        "The role must be one of " + Arrays.toString(values())));
    }
}
