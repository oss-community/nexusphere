package com.nexusphere.e2e.support;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

public final class DelegationApi {

    private DelegationApi() {
    }

    public static String delegations(String networkId) {
        return "/api/v1/networks/" + networkId + "/delegations";
    }

    public static ApiClient.Response grant(ApiClient as, String networkId, String delegate, String actions,
                                           String constraints, String validUntil) {
        String limits = constraints == null ? "" : ",\"constraints\":" + constraints;
        String until = validUntil == null ? "" : ",\"validUntil\":\"" + validUntil + "\"";
        return as.post(delegations(networkId), "{\"delegatePrincipalId\":\"" + delegate + "\",\"actions\":[" + actions
                + "]" + limits + until + "}");
    }

    public static String granted(ApiClient as, String networkId, String delegate, String actions) {
        ApiClient.Response granted = grant(as, networkId, delegate, actions, null, null);
        assertThat(granted.status()).as(granted.body()).isEqualTo(201);
        return granted.json().path("id").asString();
    }

    public static ApiClient.Response transition(ApiClient as, String networkId, String delegationId, String action) {
        return as.post(delegations(networkId) + "/" + delegationId + "/" + action, "");
    }

    public static String principalId(ApiClient as) {
        return as.get("/api/v1/principal").json().path("principalId").asString();
    }

    public static ApiClient.Response assign(ApiClient as, String networkId, String principalId, String role) {
        return as.post("/api/v1/networks/" + networkId + "/role-assignments",
                "{\"principalId\":\"" + principalId + "\",\"role\":\"" + role + "\"}");
    }

    public static JsonNode evaluate(ApiClient as, String action, String targetNetwork, String capabilityType,
                                    String delegationId) {
        String type = capabilityType == null ? "" : ",\"capabilityTypeCode\":\"" + capabilityType + "\"";
        String delegation = delegationId == null ? "" : ",\"delegationId\":\"" + delegationId + "\"";
        ApiClient.Response response = as.post("/api/v1/authorization/evaluate", "{\"action\":\"" + action
                + "\",\"resource\":{\"type\":\"agreement\",\"id\":\"draft\",\"networkId\":\"" + targetNetwork + "\"}"
                + type + delegation + "}");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }
}
