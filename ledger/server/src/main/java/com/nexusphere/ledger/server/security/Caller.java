package com.nexusphere.ledger.server.security;

import com.nexusphere.ledger.server.web.LedgerException;

public record Caller(Role role, String agentId, PrincipalIdentity principal) {

    public enum Role {
        OPERATOR,
        AGENT,
        PRINCIPAL
    }

    public static final String ATTRIBUTE = Caller.class.getName();

    private static final Caller OPERATOR = new Caller(Role.OPERATOR, null, null);

    public static Caller operator() {
        return OPERATOR;
    }

    public static Caller agent(String agentId) {
        return new Caller(Role.AGENT, agentId, null);
    }

    public static Caller principal(PrincipalIdentity principal) {
        return new Caller(Role.PRINCIPAL, null, principal);
    }

    public boolean isOperator() {
        return role == Role.OPERATOR;
    }

    public boolean isAgent() {
        return role == Role.AGENT;
    }

    public void requireOperator() {
        if (!isOperator()) {
            throw LedgerException.forbidden("Only the ledger operator may do this.");
        }
    }

    public PrincipalIdentity requirePrincipal() {
        if (role != Role.PRINCIPAL) {
            throw LedgerException.forbidden("Only a signed-in principal may do this.");
        }
        return principal;
    }

    public boolean canActAs(String agentId) {
        return isOperator() || (isAgent() && this.agentId.equals(agentId));
    }
}
