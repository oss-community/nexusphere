package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.server.web.LedgerException;

import java.util.LinkedHashMap;
import java.util.Map;

final class Paging {

    private Paging() {
    }

    static void check(long after, int limit, int maxLimit) {
        Map<String, Object> errors = new LinkedHashMap<>();
        if (after < 0) {
            errors.put("after", "must not be negative");
        }
        if (limit < 1 || limit > maxLimit) {
            errors.put("limit", "must be between 1 and " + maxLimit);
        }
        if (!errors.isEmpty()) {
            throw LedgerException.invalid("The query has invalid parameters.", errors);
        }
    }
}
