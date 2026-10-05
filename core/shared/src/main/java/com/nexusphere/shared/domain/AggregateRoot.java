package com.nexusphere.shared.domain;

import com.nexusphere.shared.event.DomainEvent;

import java.util.ArrayList;
import java.util.List;

/** Base for aggregates that record domain events until the application layer publishes them. */
public abstract class AggregateRoot<ID> {

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    public abstract ID id();

    protected void registerEvent(DomainEvent event) {
        pendingEvents.add(event);
    }

    /** Returns the recorded events and clears them, so each event is published once. */
    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }
}
