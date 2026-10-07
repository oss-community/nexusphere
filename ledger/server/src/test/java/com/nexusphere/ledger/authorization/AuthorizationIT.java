package com.nexusphere.ledger.authorization;

import com.nexusphere.ledger.LedgerContainers;
import com.nexusphere.ledger.agent.application.AgentService;
import com.nexusphere.ledger.agent.domain.model.AgentRegistration;
import com.nexusphere.ledger.authorization.application.DecisionService;
import com.nexusphere.ledger.authorization.application.GrantService;
import com.nexusphere.ledger.authorization.domain.model.DecisionRequest;
import com.nexusphere.ledger.authorization.domain.model.DecisionResult;
import com.nexusphere.ledger.authorization.domain.model.Grant;
import com.nexusphere.ledger.authorization.domain.model.GrantRequest;
import com.nexusphere.ledger.authorization.domain.model.GrantStatus;
import com.nexusphere.ledger.authorization.domain.model.ReasonCode;
import com.nexusphere.ledger.evidence.application.VerificationService;
import com.nexusphere.ledger.evidence.domain.model.Decision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles({"postgresql", "dev"})
@Import(LedgerContainers.class)
class AuthorizationIT {

    @Autowired
    AgentService agents;

    @Autowired
    GrantService grants;

    @Autowired
    DecisionService decisions;

    @Autowired
    VerificationService verification;

    @Test
    void concurrentDecisionsNeverExceedTheUsesOfAGrant() throws Exception {
        String agent = "agent-" + UUID.randomUUID();
        agents.register(new AgentRegistration(agent, "Concurrent agent", "acme"));
        Grant grant = grants.create(new GrantRequest("dana", agent, List.of("tools/call"), List.of("search"),
                null, Instant.now().plus(1, ChronoUnit.HOURS), 5L, null));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<DecisionResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            tasks.add(() -> decisions.decide(new DecisionRequest(agent, "dana", "tools/call", "search", null, null,
                    Map.of())));
        }
        List<DecisionResult> results = new ArrayList<>();
        for (Future<DecisionResult> future : pool.invokeAll(tasks)) {
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(results.stream().filter(r -> r.decision().decision() == Decision.ALLOW)).hasSize(5);
        assertThat(results.stream().filter(r -> r.decision().reasonCode() == ReasonCode.USES_EXHAUSTED)).hasSize(19);
        assertThat(grants.get(grant.terms().id()).uses()).isEqualTo(5);
        assertThat(grants.get(grant.terms().id()).status(Instant.now())).isEqualTo(GrantStatus.EXHAUSTED);
        assertThat(verification.verify().valid()).isTrue();
    }
}
