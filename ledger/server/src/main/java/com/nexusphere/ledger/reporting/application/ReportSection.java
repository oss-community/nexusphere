package com.nexusphere.ledger.reporting.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReportSection(String id, String reference, String requirement, Status status, List<String> findings,
                            List<Long> entries) {

    public static final int MAX_FINDINGS = 1000;

    public enum Status {
        SHOWN, PARTLY_SHOWN, NOT_SHOWN;

        public static Status share(long matching, long total) {
            if (total > 0 && matching == total) {
                return SHOWN;
            }
            return matching > 0 ? PARTLY_SHOWN : NOT_SHOWN;
        }
    }

    public ReportSection {
        findings = List.copyOf(findings);
        entries = List.copyOf(entries == null ? List.of() : entries);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("reference", reference);
        map.put("requirement", requirement);
        map.put("status", status.name());
        map.put("findings", findings);
        map.put("entries", entries);
        return map;
    }
}
