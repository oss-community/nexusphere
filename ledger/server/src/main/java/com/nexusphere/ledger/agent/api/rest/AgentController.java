package com.nexusphere.ledger.agent.api.rest;

import com.nexusphere.ledger.agent.application.AgentService;
import com.nexusphere.ledger.agent.domain.model.Agent;
import com.nexusphere.ledger.agent.domain.model.AgentRegistration;
import com.nexusphere.ledger.agent.domain.model.IssuedAgent;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/agents")
class AgentController {

    record AgentRequest(String agentId, String name, String ownerId) {
    }

    record AgentResponse(String agentId, String name, String ownerId, String status, String keyPrefix,
                         Instant createdAt, String signingKey, String signingKeyId, Instant signingKeySetAt) {

        static AgentResponse of(Agent a) {
            return new AgentResponse(a.agentId(), a.name(), a.ownerId(), a.status().name(), a.keyPrefix(),
                    a.createdAt(), a.signingKey(), a.signingKeyId(), a.signingKeySetAt());
        }
    }

    record SigningKeyRequest(String publicKey) {
    }

    record IssuedAgentResponse(String agentId, String name, String ownerId, String status, String keyPrefix,
                               Instant createdAt, String apiKey) {

        static IssuedAgentResponse of(IssuedAgent issued) {
            Agent a = issued.agent();
            return new IssuedAgentResponse(a.agentId(), a.name(), a.ownerId(), a.status().name(), a.keyPrefix(),
                    a.createdAt(), issued.apiKey());
        }
    }

    record AgentPage(List<AgentResponse> items, String nextAfter) {
    }

    private final AgentService agents;

    AgentController(AgentService agents) {
        this.agents = agents;
    }

    @PostMapping
    ResponseEntity<IssuedAgentResponse> register(Caller caller, @RequestBody AgentRequest request) {
        caller.requireOperator();
        IssuedAgent issued = agents.register(new AgentRegistration(request.agentId(), request.name(),
                request.ownerId()));
        return ResponseEntity.created(URI.create("/api/v1/agents/" + issued.agent().agentId()))
                .body(IssuedAgentResponse.of(issued));
    }

    @GetMapping("/{agentId}")
    AgentResponse get(Caller caller, @PathVariable String agentId) {
        if (!caller.canActAs(agentId)) {
            throw LedgerException.notFound("Agent " + agentId);
        }
        return AgentResponse.of(agents.get(agentId));
    }

    @GetMapping
    AgentPage list(Caller caller, @RequestParam(defaultValue = "") String after,
                   @RequestParam(defaultValue = "100") int limit) {
        caller.requireOperator();
        List<Agent> page = agents.list(after, limit);
        String nextAfter = page.size() == limit ? page.getLast().agentId() : null;
        return new AgentPage(page.stream().map(AgentResponse::of).toList(), nextAfter);
    }

    @PostMapping("/{agentId}/disable")
    AgentResponse disable(Caller caller, @PathVariable String agentId) {
        caller.requireOperator();
        return AgentResponse.of(agents.disable(agentId));
    }

    @PutMapping("/{agentId}/signing-key")
    AgentResponse setSigningKey(Caller caller, @PathVariable String agentId, @RequestBody SigningKeyRequest request) {
        if (!caller.canActAs(agentId)) {
            throw LedgerException.notFound("Agent " + agentId);
        }
        return AgentResponse.of(agents.setSigningKey(agentId, request.publicKey(), caller.isOperator()));
    }

    @PostMapping("/{agentId}/key")
    IssuedAgentResponse rotateKey(Caller caller, @PathVariable String agentId) {
        caller.requireOperator();
        return IssuedAgentResponse.of(agents.rotateKey(agentId));
    }
}
