package com.nexusphere.shared.event;

import com.nexusphere.shared.context.ExecutionContext;

import java.util.Collection;

/**
 * Port for publishing domain events. Version 1 dispatches in-process; a broker-backed
 * implementation can replace it without domain changes (redesign §51).
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event, ExecutionContext context);

    default void publishAll(Collection<? extends DomainEvent> events, ExecutionContext context) {
        events.forEach(event -> publish(event, context));
    }
}
