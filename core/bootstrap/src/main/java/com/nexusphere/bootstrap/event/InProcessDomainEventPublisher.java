package com.nexusphere.bootstrap.event;

import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.event.EventEnvelope;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Version 1 event dispatch: domain events travel inside the process as {@link EventEnvelope}s.
 * Listeners in other modules subscribe with {@code @ApplicationModuleListener} or {@code @EventListener}.
 */
@Component
class InProcessDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher publisher;

    InProcessDomainEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(DomainEvent event, ExecutionContext context) {
        publisher.publishEvent(EventEnvelope.wrap(event, context, null));
    }
}
