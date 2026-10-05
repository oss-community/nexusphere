package com.nexusphere.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

public final class FederationApi {

    private FederationApi() {
    }

    public static String trustRelationships(String networkId) {
        return "/api/v1/networks/" + networkId + "/trust-relationships";
    }

    public static String federations(String networkId) {
        return "/api/v1/networks/" + networkId + "/federations";
    }

    public static ApiClient.Response trustNetwork(ApiClient as, String sourceNetwork, String targetNetwork,
                                                  String scopes, String effectiveUntil) {
        String until = effectiveUntil == null ? "" : ",\"effectiveUntil\":\"" + effectiveUntil + "\"";
        return as.post(trustRelationships(sourceNetwork), "{\"target\":{\"type\":\"NETWORK\",\"id\":\""
                + targetNetwork + "\"},\"scopes\":[" + scopes + "]" + until + "}");
    }

    public static boolean trusted(ApiClient as, String callerNetwork, String sourceNetwork, String targetNetwork,
                                  String scope) {
        ApiClient.Response evaluation = as.get(trustRelationships(callerNetwork) + "/evaluation?sourceType=NETWORK"
                + "&sourceId=" + sourceNetwork + "&targetType=NETWORK&targetId=" + targetNetwork + "&scope=" + scope);
        assertThat(evaluation.status()).as(evaluation.body()).isEqualTo(200);
        return evaluation.json().path("trusted").asBoolean();
    }

    public static ApiClient.Response propose(ApiClient as, String networkId, String partner, String scopes) {
        return as.post(federations(networkId), "{\"partnerNetworkId\":\"" + partner + "\",\"scopes\":[" + scopes + "]}");
    }

    public static String proposed(ApiClient as, String networkId, String partner) {
        ApiClient.Response proposed = propose(as, networkId, partner, "\"CAPABILITY_DISCOVERY\",\"AGREEMENT_CREATION\"");
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        return proposed.json().path("id").asString();
    }

    public static ApiClient.Response transition(ApiClient as, String networkId, String federationId, String action) {
        return as.post(federations(networkId) + "/" + federationId + "/" + action, "");
    }

    public static ApiClient.Response transition(ApiClient as, String networkId, String federationId, String action,
                                                long version) {
        return as.post(federations(networkId) + "/" + federationId + "/" + action, "{\"version\":" + version + "}");
    }

    public static String active(ApiClient proposer, String proposerNetwork, ApiClient partner, String partnerNetwork) {
        String federation = proposed(proposer, proposerNetwork, partnerNetwork);
        assertThat(transition(proposer, proposerNetwork, federation, "submit").status()).isEqualTo(200);
        assertThat(transition(partner, partnerNetwork, federation, "accept").status()).isEqualTo(200);
        return federation;
    }
}
