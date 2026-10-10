package com.nexusphere.ledger.chain;

import java.util.LinkedHashMap;
import java.util.Map;

public record AgentModel(String name, String version, String promptHash, String configHash) {

    public AgentModel {
        if (promptHash != null && !Hashes.isSha256(promptHash)) {
            throw new IllegalArgumentException("promptHash must be a lowercase hex SHA-256 digest");
        }
        if (configHash != null && !Hashes.isSha256(configHash)) {
            throw new IllegalArgumentException("configHash must be a lowercase hex SHA-256 digest");
        }
    }

    public static AgentModel of(String name, String version, String promptHash, String configHash) {
        if (name == null && version == null && promptHash == null && configHash == null) {
            return null;
        }
        return new AgentModel(name, version, promptHash, configHash);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (name != null) {
            map.put("name", name);
        }
        if (version != null) {
            map.put("version", version);
        }
        if (promptHash != null) {
            map.put("promptHash", promptHash);
        }
        if (configHash != null) {
            map.put("configHash", configHash);
        }
        return map;
    }
}
