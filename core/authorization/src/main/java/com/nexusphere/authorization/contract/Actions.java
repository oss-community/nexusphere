package com.nexusphere.authorization.contract;

import java.util.Set;

public final class Actions {

    public static final String CAPABILITY_DISCOVER = "capability:discover";
    public static final String CAPABILITY_REGISTER = "capability:register";
    public static final String CAPABILITY_PUBLISH = "capability:publish";
    public static final String CAPABILITY_INVOKE = "capability:invoke";
    public static final String IDENTITY_READ = "identity:read";
    public static final String AGREEMENT_PROPOSE = "agreement:propose";
    public static final String AGREEMENT_ACCEPT = "agreement:accept";
    public static final String AGREEMENT_MANAGE = "agreement:manage";
    public static final String TRANSACTION_INITIATE = "transaction:initiate";
    public static final String TRANSACTION_EXECUTE = "transaction:execute";
    public static final String DELEGATION_GRANT = "delegation:grant";
    public static final String TRUST_MANAGE = "trust:manage";
    public static final String FEDERATION_MANAGE = "federation:manage";
    public static final String ROLE_ASSIGN = "role:assign";
    public static final String AUDIT_READ = "audit:read";

    public static final Set<String> ALL = Set.of(CAPABILITY_DISCOVER, CAPABILITY_REGISTER, CAPABILITY_PUBLISH,
            CAPABILITY_INVOKE, IDENTITY_READ, AGREEMENT_PROPOSE, AGREEMENT_ACCEPT, AGREEMENT_MANAGE,
            TRANSACTION_INITIATE, TRANSACTION_EXECUTE, DELEGATION_GRANT, TRUST_MANAGE, FEDERATION_MANAGE,
            ROLE_ASSIGN, AUDIT_READ);

    private Actions() {
    }
}
