package com.nexusphere.ledger.server.web;

import com.nexusphere.ledger.chain.AgentModel;
import com.nexusphere.ledger.chain.Hashes;
import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ModelFields {

    public static final String MODEL = "X-Ledger-Model";
    public static final String VERSION = "X-Ledger-Model-Version";
    public static final String PROMPT_HASH = "X-Ledger-Prompt-Hash";
    public static final String CONFIG_HASH = "X-Ledger-Config-Hash";

    private ModelFields() {
    }

    public static AgentModel of(String name, String version, String promptHash, String configHash, String message) {
        Map<String, Object> errors = new LinkedHashMap<>();
        if (name != null && (name.isBlank() || name.length() > 200)) {
            errors.put("model.name", "must be 1 to 200 characters");
        }
        if (version != null && (version.isBlank() || version.length() > 120)) {
            errors.put("model.version", "must be 1 to 120 characters");
        }
        if (promptHash != null && !Hashes.isSha256(promptHash)) {
            errors.put("model.promptHash", "must be a lowercase hex SHA-256 digest");
        }
        if (configHash != null && !Hashes.isSha256(configHash)) {
            errors.put("model.configHash", "must be a lowercase hex SHA-256 digest");
        }
        if (!errors.isEmpty()) {
            throw LedgerException.invalid(message, errors);
        }
        return AgentModel.of(name, version, promptHash, configHash);
    }

    public static AgentModel headers(HttpHeaders headers) {
        return of(headers.getFirst(MODEL), headers.getFirst(VERSION), headers.getFirst(PROMPT_HASH),
                headers.getFirst(CONFIG_HASH), "The model headers are not valid.");
    }
}
