package com.nexusphere.e2e.support;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public final class SovereigntyApi {

    private final ApiClient api;

    public SovereigntyApi(ApiClient api) {
        this.api = api;
    }

    public static String unique(String name) {
        return name + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    public ApiClient.Response createNetwork(String name) {
        return api.post("/api/v1/networks", "{\"name\":\"" + name + "\"}");
    }

    public String activeNetwork(String name) {
        ApiClient.Response created = createNetwork(unique(name));
        assertThat(created.status()).isEqualTo(201);
        String id = created.json().path("id").asString();
        assertThat(transition(id, "activate").status()).isEqualTo(200);
        return id;
    }

    public ApiClient.Response transition(String networkId, String action) {
        return api.post("/api/v1/networks/" + networkId + "/" + action, "");
    }

    public ApiClient.Response registerOrganization(String networkId, String name) {
        return api.post(organizations(networkId), "{\"name\":\"" + name + "\"}");
    }

    public String organization(String networkId, String name) {
        ApiClient.Response registered = registerOrganization(networkId, name);
        assertThat(registered.status()).isEqualTo(201);
        return registered.json().path("id").asString();
    }

    public ApiClient.Response listOrganizations(String networkId) {
        return api.get(organizations(networkId));
    }

    public ApiClient.Response getOrganization(String networkId, String organizationId) {
        return api.get(organizations(networkId) + "/" + organizationId);
    }

    public ApiClient.Response renameOrganization(String networkId, String organizationId, String name) {
        return api.put(organizations(networkId) + "/" + organizationId, "{\"name\":\"" + name + "\"}");
    }

    public ApiClient.Response deactivateOrganization(String networkId, String organizationId) {
        return api.post(organizations(networkId) + "/" + organizationId + "/deactivate", "");
    }

    public static String organizations(String networkId) {
        return "/api/v1/networks/" + networkId + "/organizations";
    }
}
