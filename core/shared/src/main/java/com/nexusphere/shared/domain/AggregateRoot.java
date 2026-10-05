package com.nexusphere.shared.domain;

import com.nexusphere.shared.event.DomainEvent;

import java.util.ArrayList;
import java.util.List;

public abstract class AggregateRoot<ID> {

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    public abstract ID id();

    protected void registerEvent(DomainEvent event) {
        pendingEvents.add(event);
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }
}
