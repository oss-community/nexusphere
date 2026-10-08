package com.nexusphere.e2e.ledger;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.IdentityApi;
import com.nexusphere.e2e.support.SovereigntyApi;
import com.nexusphere.integration.application.LedgerForwarder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.stream.StreamSupport;

import static com.nexusphere.e2e.support.DelegationApi.assign;
import static com.nexusphere.e2e.support.DelegationApi.evaluate;
import static com.nexusphere.e2e.support.DelegationApi.granted;
import static com.nexusphere.e2e.support.DelegationApi.transition;
import static org.assertj.core.api.Assertions.assertThat;

class LedgerIntegrationE2ETest extends E2ETestBase {

    private static final FakeLedger LEDGER = new FakeLedger();

    @DynamicPropertySource
    static void ledger(DynamicPropertyRegistry registry) {
        registry.add("nexusphere.ledger.url", LEDGER::url);
        registry.add("nexusphere.ledger.api-key", () -> FakeLedger.API_KEY);
        registry.add("nexusphere.ledger.forward-interval", () -> "1h");
    }

    @Autowired
    LedgerForwarder forwarder;

    private String network;
    private ApiClient manager;
    private ApiClient agent;
    private ApiClient bob;
    private String managerPrincipal;
    private String agentPrincipal;

    @BeforeEach
    void ledgerSetUp() {
        SovereigntyApi sovereignty = new SovereigntyApi(api());
        IdentityApi identities = new IdentityApi(api());
        network = sovereignty.activeNetwork("Ledger Network");
        String organization = sovereignty.organization(network, "Ledger Organization");
        String adminId = identities.human("Ledger Admin");
        identities.administrator(network, adminId);
        ApiClient admin = identities.as(identities.actor(adminId), network);
        String managerId = identities.human("Ledger Manager");
        managerPrincipal = identities.member(network, managerId, organization);
        manager = identities.as(identities.actor(managerId), network);
        String agentId = identities.owned("AGENT", "Ledger Agent", network, organization);
        agentPrincipal = identities.member(network, agentId, organization);
        agent = identities.as(identities.actor(agentId), network);
        String bobId = identities.human("Ledger Bob");
        identities.member(network, bobId);
        bob = identities.as(identities.actor(bobId), network);
        assertThat(assign(admin, network, managerPrincipal, "AGREEMENT_MANAGER").status()).isEqualTo(201);
        LEDGER.evidenceStatus(201);
        drain();
    }

    @Test
    @DisplayName("E2E-LDG-01 a delegation becomes a ledger grant, its decisions become evidence and its delegate gets a mandate")
    void delegationReachesTheLedger() {
        String delegation = granted(manager, network, agentPrincipal, "\"agreement:propose\"");
        ApiClient.Response early = mandate(agent, delegation);
        assertThat(early.status()).as(early.body()).isEqualTo(409);
        assertThat(early.json().path("code").asString()).isEqualTo("DELEGATION_NOT_IN_LEDGER");
        assertThat(evaluate(agent, "agreement:propose", network, null, delegation).path("result").asString())
                .isEqualTo("ALLOW");

        drain();

        assertThat(LEDGER.calls("/api/v1/agents")).anySatisfy(call ->
                assertThat(call.body().path("agentId").asString()).isEqualTo(agentPrincipal));
        FakeLedger.Call grant = LEDGER.calls("/api/v1/grants").stream()
                .filter(call -> call.body().path("reason").asString().equals("Nexusphere delegation " + delegation))
                .findFirst().orElseThrow();
        assertThat(grant.body().path("principalId").asString()).isEqualTo(managerPrincipal);
        assertThat(grant.body().path("agentId").asString()).isEqualTo(agentPrincipal);
        assertThat(texts(grant.body().path("actions"))).containsExactly("agreement:propose");
        assertThat(texts(grant.body().path("targets"))).containsExactly("*");
        assertThat(grant.body().path("expiresAt").isString()).isTrue();

        List<JsonNode> evidence = evidenceFor(delegation);
        assertThat(evidence).anySatisfy(e -> {
            assertThat(e.path("action").asString()).isEqualTo("delegation/grant");
            assertThat(e.path("agentId").asString()).isEqualTo(agentPrincipal);
            assertThat(e.path("principalId").asString()).isEqualTo(managerPrincipal);
            assertThat(e.path("outcome").asString()).isEqualTo("SUCCEEDED");
        });
        assertThat(evidence).anySatisfy(e -> {
            assertThat(e.path("action").asString()).isEqualTo("agreement:propose");
            assertThat(e.path("decision").asString()).isEqualTo("ALLOW");
            assertThat(e.path("agentId").asString()).isEqualTo(agentPrincipal);
            assertThat(e.path("principalId").asString()).isEqualTo(managerPrincipal);
            assertThat(e.path("attributes").path("core.decisionId").isString()).isTrue();
            assertThat(e.path("attributes").path("core.network").asString()).isEqualTo(network);
        });

        ApiClient.Response mandate = mandate(agent, delegation);
        assertThat(mandate.status()).as(mandate.body()).isEqualTo(201);
        assertThat(mandate.json().path("token").asString()).isEqualTo("mandate-token");
        assertThat(mandate.json().path("audience").asString()).isEqualTo("https://supplier.example");
        assertThat(mandate(manager, delegation).status()).isEqualTo(201);
        ApiClient.Response stranger = mandate(bob, delegation);
        assertThat(stranger.status()).as(stranger.body()).isEqualTo(403);
        assertThat(stranger.json().path("code").asString()).isEqualTo("NOT_DELEGATION_PARTY");

        assertThat(transition(manager, network, delegation, "revoke").status()).isEqualTo(200);
        drain();

        String grantId = LEDGER.calls("/api/v1/mandates").getFirst().body().path("grantId").asString();
        assertThat(LEDGER.calls("/api/v1/grants/" + grantId + "/revoke")).hasSize(1);
        assertThat(evidenceFor(delegation)).anySatisfy(e ->
                assertThat(e.path("action").asString()).isEqualTo("delegation/revoke"));
        ApiClient.Response revoked = mandate(agent, delegation);
        assertThat(revoked.status()).isEqualTo(409);
        assertThat(revoked.json().path("code").asString()).isEqualTo("DELEGATION_NOT_ACTIVE");
    }

    @Test
    @DisplayName("E2E-LDG-02 suspending and resuming a delegation revokes its grant and creates a new one")
    void suspendAndResumeFollowTheDelegation() {
        String delegation = granted(manager, network, agentPrincipal, "\"agreement:propose\"");
        drain();
        assertThat(transition(manager, network, delegation, "suspend").status()).isEqualTo(200);
        drain();
        assertThat(mandate(agent, delegation).status()).isEqualTo(409);
        assertThat(transition(manager, network, delegation, "resume").status()).isEqualTo(200);
        drain();

        assertThat(LEDGER.calls("/api/v1/grants").stream()
                .filter(call -> call.body().path("reason").asString().equals("Nexusphere delegation " + delegation)))
                .hasSize(2);
        assertThat(LEDGER.callsEndingWith("/revoke")).isNotEmpty();
        assertThat(mandate(agent, delegation).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("E2E-LDG-03 evidence waits while the ledger is down, and an entry the ledger refuses does not block the rest")
    void outboxSurvivesAnOutage() {
        LEDGER.evidenceStatus(503);
        String delegation = granted(manager, network, agentPrincipal, "\"agreement:propose\"");
        assertThat(forwarder.forward()).isZero();
        assertThat(evidenceFor(delegation)).isEmpty();

        LEDGER.evidenceStatus(400);
        forwarder.forward();
        LEDGER.evidenceStatus(201);
        String second = granted(manager, network, agentPrincipal, "\"agreement:propose\"");
        drain();

        assertThat(evidenceFor(delegation)).isEmpty();
        assertThat(evidenceFor(second)).anySatisfy(e ->
                assertThat(e.path("action").asString()).isEqualTo("delegation/grant"));
    }

    private void drain() {
        for (int i = 0; i < 100 && forwarder.forward() > 0; i++) {
            assertThat(i).isLessThan(99);
        }
    }

    private ApiClient.Response mandate(ApiClient as, String delegation) {
        return as.post("/api/v1/networks/" + network + "/delegations/" + delegation + "/mandates",
                "{\"audience\":\"https://supplier.example\"}");
    }

    private static List<JsonNode> evidenceFor(String delegation) {
        return LEDGER.calls("/api/v1/evidence").stream().map(FakeLedger.Call::body)
                .filter(body -> body.path("delegationId").asString("").equals(delegation)).toList();
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
    }
}
