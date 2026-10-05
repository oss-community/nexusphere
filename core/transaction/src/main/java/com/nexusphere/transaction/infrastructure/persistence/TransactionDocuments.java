package com.nexusphere.transaction.infrastructure.persistence;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Component
class TransactionDocuments {

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {
    };

    private final JsonMapper json;

    TransactionDocuments(JsonMapper json) {
        this.json = json;
    }

    String write(Map<String, Object> document) {
        return document == null ? null : json.writeValueAsString(document);
    }

    Map<String, Object> read(String document) {
        return document == null ? null : json.readValue(document, DOCUMENT);
    }
}
