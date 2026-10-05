package com.nexusphere.capability.infrastructure.persistence;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Component
class JsonDocuments {

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {
    };

    private final JsonMapper json;

    JsonDocuments(JsonMapper json) {
        this.json = json;
    }

    String write(Map<String, Object> document) {
        return json.writeValueAsString(document);
    }

    Map<String, Object> read(String document) {
        return json.readValue(document, DOCUMENT);
    }
}
