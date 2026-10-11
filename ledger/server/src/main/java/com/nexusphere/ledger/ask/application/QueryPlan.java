package com.nexusphere.ledger.ask.application;

import java.time.Instant;

public record QueryPlan(String agentId, String principalId, String action, String target, String decision,
                        String outcome, Instant from, Instant to) {

    public static final QueryPlan EVERYTHING = new QueryPlan(null, null, null, null, null, null, null, null);

    public QueryPlan scopedTo(String principal) {
        return principal == null ? this : new QueryPlan(agentId, principal, action, target, decision, outcome, from, to);
    }
}
