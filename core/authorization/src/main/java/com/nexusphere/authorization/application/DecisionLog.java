package com.nexusphere.authorization.application;

import com.nexusphere.authorization.contract.AuthorizationDecision;
import com.nexusphere.authorization.contract.AuthorizationDenied;
import com.nexusphere.authorization.contract.AuthorizationGranted;
import com.nexusphere.authorization.domain.repository.AuthorizationDecisionRepository;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.event.DomainEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class DecisionLog {

    private final AuthorizationDecisionRepository decisions;
    private final DomainEventPublisher events;

    DecisionLog(AuthorizationDecisionRepository decisions, DomainEventPublisher events) {
        this.decisions = decisions;
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    AuthorizationDecision record(AuthorizationDecision decision, ExecutionContext context) {
        AuthorizationDecision saved = decisions.save(decision);
        events.publish(decision.allowed() ? new AuthorizationGranted(saved) : new AuthorizationDenied(saved),
                context.withNetwork(decision.networkId()));
        return saved;
    }
}
