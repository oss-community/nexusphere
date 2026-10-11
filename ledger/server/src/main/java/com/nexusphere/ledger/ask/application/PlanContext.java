package com.nexusphere.ledger.ask.application;

import java.time.Instant;
import java.util.List;

public record PlanContext(Instant now, List<String> agents, List<String> actions, String principal) {
}
