package com.nexusphere.shared.event;

import com.nexusphere.shared.context.ExecutionContext;

import java.util.Collection;

public interface DomainEventPublisher {

    void publish(DomainEvent event, ExecutionContext context);

    default void publishAll(Collection<? extends DomainEvent> events, ExecutionContext context) {
        events.forEach(event -> publish(event, context));
    }
}
