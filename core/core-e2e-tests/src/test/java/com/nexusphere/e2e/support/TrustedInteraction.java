package com.nexusphere.e2e.support;

import static com.nexusphere.e2e.support.CapabilityApi.body;
import static com.nexusphere.e2e.support.CapabilityApi.published;
import static com.nexusphere.e2e.support.DelegationApi.assign;
import static com.nexusphere.e2e.support.DelegationApi.grant;
import static com.nexusphere.e2e.support.DelegationApi.principalId;
import static com.nexusphere.e2e.support.FederationApi.active;
import static com.nexusphere.e2e.support.FederationApi.trustNetwork;
import static org.assertj.core.api.Assertions.assertThat;

public final class TrustedInteraction {

    public static final String SCOPES = "\"capability:discover\",\"agreement:propose\",\"agreement:accept\","
            + "\"agreement:manage\",\"transaction:initiate\",\"transaction:execute\"";

    public final ApiClient api;
    public final IdentityApi identities;
    public final SovereigntyApi sovereignty;
    public final String networkA;
    public final String networkB;
    public final String organizationA;
    public final String organizationB;
    public final ApiClient adminA;
    public final ApiClient adminB;
    public final ApiClient humanA;
    public final ApiClient agentA;
    public final ApiClient humanB;
    public final String humanAIdentity;
    public final String humanBIdentity;
    public final String humanAPrincipal;
    public final String agentAPrincipal;
    public final String agentAIdentity;
    public final String humanBPrincipal;
    public final String cnc;
    public final String capability;
    public final String federation;
    public final String trustBA;
    public final String trustAB;
    public final String delegation;

    private TrustedInteraction(ApiClient api, String delegatedActions) {
        this.api = api;
        identities = new IdentityApi(api);
        sovereignty = new SovereigntyApi(api);
        CapabilityApi capabilityApi = new CapabilityApi(api);

        networkA = sovereignty.activeNetwork("Network A");
        organizationA = sovereignty.organization(networkA, "Organization A");
        adminA = administrator(networkA, "Admin A");
        String humanAId = identities.human("Human A");
        humanAIdentity = humanAId;
        humanAPrincipal = identities.member(networkA, humanAId, organizationA);
        humanA = identities.as(identities.actor(humanAId), networkA);
        assertThat(assign(adminA, networkA, humanAPrincipal, "AGREEMENT_MANAGER").status()).isEqualTo(201);
        assertThat(assign(adminA, networkA, humanAPrincipal, "TRANSACTION_OPERATOR").status()).isEqualTo(201);
        agentAIdentity = identities.owned("AGENT", "Agent A", networkA, organizationA);
        agentAPrincipal = identities.member(networkA, agentAIdentity, organizationA);
        agentA = identities.as(identities.actor(agentAIdentity), networkA);

        networkB = sovereignty.activeNetwork("Network B");
        organizationB = sovereignty.organization(networkB, "Organization B");
        adminB = administrator(networkB, "Admin B");
        String humanBId = identities.human("Human B");
        humanBIdentity = humanBId;
        humanBPrincipal = identities.member(networkB, humanBId, organizationB);
        humanB = identities.as(identities.actor(humanBId), networkB);
        assertThat(assign(adminB, networkB, humanBPrincipal, "AGREEMENT_MANAGER").status()).isEqualTo(201);
        assertThat(assign(adminB, networkB, humanBPrincipal, "TRANSACTION_OPERATOR").status()).isEqualTo(201);

        cnc = capabilityApi.type(CapabilityApi.uniqueCode("manufacturing.cnc"), CapabilityApi.CNC_SCHEMA);
        capability = published(humanB, networkB, body("CNC Machining", cnc, null, null, "FEDERATED"));

        trustBA = trustNetwork(adminB, networkB, networkA, SCOPES, null).json().path("id").asString();
        trustAB = trustNetwork(adminA, networkA, networkB, SCOPES, null).json().path("id").asString();
        federation = FederationApi.activeWith(adminA, networkA, adminB, networkB,
                "\"CAPABILITY_DISCOVERY\",\"AGREEMENT_CREATION\",\"TRANSACTION_EXCHANGE\"");

        ApiClient.Response granted = grant(humanA, networkA, agentAPrincipal, delegatedActions,
                "{\"capabilityTypes\":[\"" + cnc + "\"],\"networks\":[\"" + networkB + "\"]}", null);
        assertThat(granted.status()).as(granted.body()).isEqualTo(201);
        delegation = granted.json().path("id").asString();
        assertThat(principalId(agentA)).isEqualTo(agentAPrincipal);
    }

    public static TrustedInteraction establish(ApiClient api) {
        return new TrustedInteraction(api, "\"agreement:propose\",\"transaction:initiate\"");
    }

    public static TrustedInteraction establish(ApiClient api, String delegatedActions) {
        return new TrustedInteraction(api, delegatedActions);
    }

    private ApiClient administrator(String networkId, String name) {
        String identity = identities.human(name);
        identities.administrator(networkId, identity);
        return identities.as(identities.actor(identity), networkId);
    }

    public static String agreements(String networkId) {
        return "/api/v1/networks/" + networkId + "/agreements";
    }

    public ApiClient.Response draft(ApiClient as, String capabilityId, String terms) {
        return as.post(agreements(networkA), "{\"capabilityId\":\"" + capabilityId
                + "\",\"title\":\"CNC parts\",\"terms\":" + terms + "}");
    }

    public String proposed() {
        ApiClient.Response draft = draft(agentA, capability, "{\"quantity\":100,\"material\":\"steel\"}");
        assertThat(draft.status()).as(draft.body()).isEqualTo(201);
        String agreement = draft.json().path("id").asString();
        ApiClient.Response proposed = agentA.post(agreements(networkA) + "/" + agreement + "/propose", "");
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(200);
        return agreement;
    }

    public static String transactions(String networkId) {
        return "/api/v1/networks/" + networkId + "/transactions";
    }

    public String completedTransaction(String agreement) {
        ApiClient.Response requested = agentA.post(transactions(networkA), "{\"agreementId\":\"" + agreement
                + "\",\"type\":\"capability.invocation\"}");
        assertThat(requested.status()).as(requested.body()).isEqualTo(201);
        String transaction = requested.json().path("id").asString();
        assertThat(humanB.post(transactions(networkB) + "/" + transaction + "/execute", "").status()).isEqualTo(200);
        ApiClient.Response completed = humanB.post(transactions(networkB) + "/" + transaction + "/complete",
                "{\"result\":{\"delivered\":100}}");
        assertThat(completed.json().path("status").asString()).isEqualTo("COMPLETED");
        return transaction;
    }

    public String activeAgreement() {
        String agreement = proposed();
        ApiClient.Response accepted = humanB.post(agreements(networkB) + "/" + agreement + "/accept",
                "{\"version\":1}");
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(200);
        ApiClient.Response activated = humanB.post(agreements(networkB) + "/" + agreement + "/activate", "");
        assertThat(activated.status()).as(activated.body()).isEqualTo(200);
        return agreement;
    }
}
