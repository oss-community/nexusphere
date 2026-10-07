package com.nexusphere.ledger.mandate;

import java.util.List;

public record MandateCheck(MandateClaims claims, List<Problem> problems) {

    public record Problem(MandateProblem code, String message) {
    }

    public MandateCheck {
        problems = List.copyOf(problems);
    }

    public boolean valid() {
        return claims != null && problems.isEmpty();
    }

    public boolean has(MandateProblem code) {
        return problems.stream().anyMatch(p -> p.code() == code);
    }
}
