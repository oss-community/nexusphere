package com.nexusphere.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

public final class IdentityApi {

    public record Actor(String identityId, String token) {
    }

    private final ApiClient api;

    public IdentityApi(ApiClient api) {
        this.api = api;
    }

    public ApiClient.Response create(String type, String displayName, String networkId, String organizationId) {
        String ownership = networkId == null ? ""
                : ",\"owningNetworkId\":\"" + networkId + "\",\"owningOrganizationId\":\"" + organizationId + "\"";
        return api.post("/api/v1/identities",
                "{\"type\":\"" + type + "\",\"displayName\":\"" + displayName + "\"" + ownership + "}");
    }

    public String human(String displayName) {
        ApiClient.Response created = create("HUMAN", displayName, null, null);
        assertThat(created.status()).isEqualTo(201);
        return created.json().path("id").asString();
    }

    public ApiClient.Response activateMembership(String networkId, String identityId) {
        return api.post("/api/v1/networks/" + networkId + "/memberships", "{\"identityId\":\"" + identityId + "\"}");
    }

    public String member(String networkId, String identityId) {
        ApiClient.Response membership = activateMembership(networkId, identityId);
        assertThat(membership.status()).isEqualTo(201);
        return membership.json().path("id").asString();
    }

    public String member(String networkId, String identityId, String organizationId) {
        ApiClient.Response membership = api.post("/api/v1/networks/" + networkId + "/memberships",
                "{\"identityId\":\"" + identityId + "\",\"organizationId\":\"" + organizationId + "\"}");
        assertThat(membership.status()).isEqualTo(201);
        return membership.json().path("id").asString();
    }

    public String administrator(String networkId, String identityId) {
        ApiClient.Response membership = api.post("/api/v1/networks/" + networkId + "/memberships",
                "{\"identityId\":\"" + identityId + "\",\"role\":\"ADMINISTRATOR\"}");
        assertThat(membership.status()).isEqualTo(201);
        return membership.json().path("id").asString();
    }

    public String owned(String type, String displayName, String networkId, String organizationId) {
        ApiClient.Response created = create(type, displayName, networkId, organizationId);
        assertThat(created.status()).isEqualTo(201);
        return created.json().path("id").asString();
    }

    public String secret(String identityId) {
        ApiClient.Response credential = api.post("/api/v1/identities/" + identityId + "/credentials", "");
        assertThat(credential.status()).isEqualTo(201);
        return credential.json().path("secret").asString();
    }

    public ApiClient.Response token(String identityId, String secret) {
        return api.post("/api/v1/auth/token", "{\"identityId\":\"" + identityId + "\",\"secret\":\"" + secret + "\"}");
    }

    public Actor actor(String identityId) {
        ApiClient.Response token = token(identityId, secret(identityId));
        assertThat(token.status()).isEqualTo(200);
        return new Actor(identityId, token.json().path("accessToken").asString());
    }

    public ApiClient as(Actor actor) {
        return api.withHeader("Authorization", "Bearer " + actor.token());
    }

    public ApiClient as(Actor actor, String networkId) {
        return as(actor).withHeader("X-Network-Id", networkId);
    }
}
