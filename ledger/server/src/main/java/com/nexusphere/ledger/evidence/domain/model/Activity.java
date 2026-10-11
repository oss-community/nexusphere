package com.nexusphere.ledger.evidence.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record Activity(Instant since, long total, long allowed, long denied, long failed, List<Day> days,
                       List<Agent> agents) {

    public record Day(LocalDate day, long total, long allowed, long denied, long failed) {
    }

    public record Agent(String agentId, long total, long allowed, long denied, long failed, Instant lastAt) {
    }
}
