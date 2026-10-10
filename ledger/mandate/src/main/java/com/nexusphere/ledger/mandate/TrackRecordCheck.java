package com.nexusphere.ledger.mandate;

import java.util.List;

public record TrackRecordCheck(TrackRecordClaims claims, List<MandateCheck.Problem> problems) {

    public TrackRecordCheck {
        problems = List.copyOf(problems);
    }

    public boolean valid() {
        return claims != null && problems.isEmpty();
    }

    public boolean has(MandateProblem code) {
        return problems.stream().anyMatch(p -> p.code() == code);
    }
}
