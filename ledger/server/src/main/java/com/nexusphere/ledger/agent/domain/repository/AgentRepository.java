package com.nexusphere.ledger.agent.domain.repository;

import com.nexusphere.ledger.agent.domain.model.Agent;
import com.nexusphere.ledger.agent.domain.model.AgentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentRepository {

    boolean insert(Agent agent, String keyHash);

    Optional<Agent> find(String agentId);

    Optional<Agent> lock(String agentId);

    List<Agent> list(String afterAgentId, int limit);

    void updateStatus(String agentId, AgentStatus status);

    void updateKey(String agentId, String keyHash, String keyPrefix);

    void updateSigningKey(String agentId, String signingKey, String signingKeyId, Instant setAt);
}
