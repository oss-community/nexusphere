package com.nexusphere.ledger.agent.application;

import com.nexusphere.ledger.agent.domain.model.Agent;
import com.nexusphere.ledger.agent.domain.model.AgentRegistration;
import com.nexusphere.ledger.agent.domain.model.AgentStatus;
import com.nexusphere.ledger.agent.domain.model.IssuedAgent;
import com.nexusphere.ledger.agent.domain.repository.AgentRepository;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.web.FieldErrors;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AgentService {

    public static final int MAX_PAGE_SIZE = 500;
    static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}");

    private final AgentRepository agents;
    private final EvidenceService evidence;
    private final Clock clock;

    AgentService(AgentRepository agents, EvidenceService evidence, Clock clock) {
        this.agents = agents;
        this.evidence = evidence;
        this.clock = clock;
    }

    @Transactional
    public IssuedAgent register(AgentRegistration registration) {
        new FieldErrors()
                .text("agentId", registration.agentId(), true, 200)
                .pattern("agentId", registration.agentId(), AGENT_ID)
                .text("name", registration.name(), true, 200)
                .text("ownerId", registration.ownerId(), true, 200)
                .throwIfAny("The agent has invalid fields.");
        String key = AgentKeys.generate();
        Agent agent = new Agent(registration.agentId(), registration.name(), registration.ownerId(),
                AgentStatus.ACTIVE, AgentKeys.prefix(key), clock.instant());
        if (!agents.insert(agent, AgentKeys.hash(key))) {
            throw LedgerException.conflict("AGENT_EXISTS", "Agent " + agent.agentId() + " already exists.");
        }
        record(agent, "agent/register", Map.of("name", agent.name(), "keyPrefix", agent.keyPrefix()));
        return new IssuedAgent(agent, key);
    }

    @Transactional
    public Agent disable(String agentId) {
        Agent agent = agents.lock(agentId).orElseThrow(() -> LedgerException.notFound("Agent " + agentId));
        if (!agent.active()) {
            return agent;
        }
        agents.updateStatus(agentId, AgentStatus.DISABLED);
        record(agent, "agent/disable", Map.of());
        return get(agentId);
    }

    @Transactional
    public IssuedAgent rotateKey(String agentId) {
        Agent agent = agents.lock(agentId).orElseThrow(() -> LedgerException.notFound("Agent " + agentId));
        if (!agent.active()) {
            throw LedgerException.conflict("AGENT_NOT_ACTIVE", "Agent " + agentId + " is disabled.");
        }
        String key = AgentKeys.generate();
        agents.updateKey(agentId, AgentKeys.hash(key), AgentKeys.prefix(key));
        record(agent, "agent/rotate-key", Map.of("keyPrefix", AgentKeys.prefix(key)));
        return new IssuedAgent(get(agentId), key);
    }

    @Transactional(readOnly = true)
    public Agent get(String agentId) {
        return agents.find(agentId).orElseThrow(() -> LedgerException.notFound("Agent " + agentId));
    }

    @Transactional(readOnly = true)
    public List<Agent> list(String afterAgentId, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw LedgerException.invalid("The query has invalid parameters.",
                    Map.of("limit", "must be between 1 and " + MAX_PAGE_SIZE));
        }
        return agents.list(afterAgentId, limit);
    }

    private void record(Agent agent, String action, Map<String, String> attributes) {
        Instant now = clock.instant();
        evidence.record(new EvidenceSubmission(now, agent.agentId(), agent.ownerId(), action, agent.agentId(),
                null, null, null, null, null, Outcome.SUCCEEDED, null, attributes));
    }
}
