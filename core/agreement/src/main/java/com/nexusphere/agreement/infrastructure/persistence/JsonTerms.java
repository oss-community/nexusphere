package com.nexusphere.agreement.infrastructure.persistence;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Component
class JsonTerms {

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {
    };

    private final JsonMapper json;

    JsonTerms(JsonMapper json) {
        this.json = json;
    }

    String write(Map<String, Object> terms) {
        return json.writeValueAsString(terms);
    }

    Map<String, Object> read(String terms) {
        return json.readValue(terms, DOCUMENT);
    }
}
