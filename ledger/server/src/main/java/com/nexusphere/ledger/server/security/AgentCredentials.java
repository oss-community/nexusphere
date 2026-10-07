package com.nexusphere.ledger.server.security;

import java.util.Optional;

public interface AgentCredentials {

    Optional<String> activeAgentByKeyHash(String keyHash);
}
