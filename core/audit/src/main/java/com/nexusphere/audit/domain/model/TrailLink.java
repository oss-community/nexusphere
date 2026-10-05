package com.nexusphere.audit.domain.model;

import java.util.Map;

public record TrailLink(String kind, String id, Map<String, String> attributes) {

    public TrailLink {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
