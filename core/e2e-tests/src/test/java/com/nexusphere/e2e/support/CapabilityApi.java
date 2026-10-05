package com.nexusphere.e2e.support;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public final class CapabilityApi {

    public static final String CNC_SCHEMA = """
            {"type":"object","required":["material","maxPartSizeMm"],"additionalProperties":false,
             "properties":{"material":{"type":"string","enum":["steel","aluminium"]},
                           "maxPartSizeMm":{"type":"integer"},
                           "certifications":{"type":"array","items":{"type":"string"}}}}""";
    public static final String CNC_SPEC = """
            {"material":"steel","maxPartSizeMm":500,"certifications":["ISO 9001"]}""";

    private final ApiClient api;

    public CapabilityApi(ApiClient api) {
        this.api = api.asOperator();
    }

    public static String uniqueCode(String prefix) {
        return prefix + ".t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    public ApiClient.Response registerType(String code, String schema) {
        return api.post("/api/v1/capability-types",
                "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"schema\":" + schema + "}");
    }

    public String type(String code, String schema) {
        ApiClient.Response registered = registerType(code, schema);
        assertThat(registered.status()).isEqualTo(201);
        return registered.json().path("code").asString();
    }

    public static ApiClient.Response register(ApiClient as, String networkId, String body) {
        return as.post(capabilities(networkId), body);
    }

    public static String registered(ApiClient as, String networkId, String body) {
        ApiClient.Response registered = register(as, networkId, body);
        assertThat(registered.status()).as(registered.body()).isEqualTo(201);
        return registered.json().path("id").asString();
    }

    public static String body(String name, String typeCode, String ownerType, String ownerId, String visibility) {
        StringBuilder body = new StringBuilder("{\"name\":\"" + name + "\",\"typeCode\":\"" + typeCode + "\"");
        if (ownerType != null) {
            body.append(",\"ownerType\":\"").append(ownerType).append("\"");
        }
        if (ownerId != null) {
            body.append(",\"ownerId\":\"").append(ownerId).append("\"");
        }
        if (visibility != null) {
            body.append(",\"visibility\":\"").append(visibility).append("\"");
        }
        return body.append(",\"specification\":").append(CNC_SPEC).append("}").toString();
    }

    public static String published(ApiClient as, String networkId, String body) {
        String id = registered(as, networkId, body);
        ApiClient.Response published = publish(as, networkId, id, null);
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        return id;
    }

    public static ApiClient.Response publish(ApiClient as, String networkId, String capabilityId, String visibility) {
        return as.post(capabilities(networkId) + "/" + capabilityId + "/publish",
                visibility == null ? "" : "{\"visibility\":\"" + visibility + "\"}");
    }

    public static ApiClient.Response withdraw(ApiClient as, String networkId, String capabilityId) {
        return as.post(capabilities(networkId) + "/" + capabilityId + "/withdraw", "");
    }

    public static String capabilities(String networkId) {
        return "/api/v1/networks/" + networkId + "/capabilities";
    }
}
