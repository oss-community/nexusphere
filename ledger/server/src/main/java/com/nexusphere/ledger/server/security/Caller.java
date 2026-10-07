package com.nexusphere.ledger.server.security;

import com.nexusphere.ledger.server.web.LedgerException;

public record Caller(Role role, String agentId) {

    public enum Role {
        OPERATOR,
        AGENT
    }

    public static final String ATTRIBUTE = Caller.class.getName();

    private static final Caller OPERATOR = new Caller(Role.OPERATOR, null);

    public static Caller operator() {
        return OPERATOR;
    }

    public static Caller agent(String agentId) {
        return new Caller(Role.AGENT, agentId);
    }

    public boolean isOperator() {
        return role == Role.OPERATOR;
    }

    public void requireOperator() {
        if (!isOperator()) {
            throw LedgerException.forbidden("Only the ledger operator may do this.");
        }
    }

    public boolean canActAs(String agentId) {
        return isOperator() || this.agentId.equals(agentId);
    }
}
