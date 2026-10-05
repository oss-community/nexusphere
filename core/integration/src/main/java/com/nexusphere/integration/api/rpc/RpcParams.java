package com.nexusphere.integration.api.rpc;

import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

final class RpcParams {

    private final JsonNode params;
    private final JsonMapper json;

    RpcParams(JsonNode params, JsonMapper json) {
        this.params = params;
        this.json = json;
    }

    String text(String name) {
        JsonNode value = params.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw invalid(name, "must be a string");
        }
        return value.asString();
    }

    String requiredText(String name) {
        String value = text(name);
        if (value == null || value.isBlank()) {
            throw invalid(name, "is required");
        }
        return value;
    }

    UUID uuid(String name) {
        String value = text(name);
        return value == null ? null : parse(name, value);
    }

    UUID requiredUuid(String name) {
        return parse(name, requiredText(name));
    }

    CapabilityId capabilityId(String name) {
        UUID value = uuid(name);
        return value == null ? null : new CapabilityId(value);
    }

    NetworkId networkId(String name) {
        UUID value = uuid(name);
        return value == null ? null : new NetworkId(value);
    }

    OrganizationId organizationId(String name) {
        UUID value = uuid(name);
        return value == null ? null : new OrganizationId(value);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> object(String name) {
        JsonNode value = params.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw invalid(name, "must be an object");
        }
        return json.convertValue(value, Map.class);
    }

    private static UUID parse(String name, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw invalid(name, "must be a UUID");
        }
    }

    private static RpcFailure invalid(String name, String problem) {
        return new RpcFailure(RpcFailure.INVALID_PARAMS, "Parameter " + name + " " + problem,
                Map.of("parameter", name));
    }
}
