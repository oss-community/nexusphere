package com.nexusphere.identity.domain.model;

public enum IdentityType {
    HUMAN,
    SERVICE,
    APPLICATION,
    AGENT,
    MACHINE;

    public boolean requiresOwningOrganization() {
        return this == AGENT || this == MACHINE;
    }
}
