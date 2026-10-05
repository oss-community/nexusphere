package com.nexusphere.e2e.audit;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.IdentityApi;
import com.nexusphere.e2e.support.TrustedInteraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static com.nexusphere.e2e.support.DelegationApi.transition;
import static com.nexusphere.e2e.support.TrustedInteraction.transactions;
import static org.assertj.core.api.Assertions.assertThat;

class AuditE2ETest extends E2ETestBase {

    private static List<String> actions(JsonNode events) {
        return events.valueStream().map(event -> event.path("action").asString()).toList();
    }

    @Test
    @DisplayName("E2E-SC16-01 the trail of a completed transaction returns the full chain and its events in causal order")
    void trailReturnsEveryElement() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        String agreement = world.activeAgreement();
        String transaction = world.completedTransaction(agreement);

        JsonNode trail = world.adminB.get("/api/v1/audit-events/trail?transactionId=" + transaction).json();

        assertThat(trail.path("chain").size()).isEqualTo(7);
        List<String> actions = actions(trail.path("events"));
        assertThat(actions).containsSubsequence("agreement:created", "agreement:proposed", "agreement:accepted",
                "agreement:activated", "transaction:requested", "transaction:authorized", "transaction:executing",
                "transaction:completed");
        assertThat(actions).contains("agreement:accept", "transaction:execute").doesNotContain("agreement:propose");
        assertThat(trail.path("events").valueStream().map(event -> event.path("networkId").asString()))
                .containsOnly(world.networkB);

        JsonNode requesterTrail = world.adminA.get("/api/v1/audit-events/trail?transactionId=" + transaction).json();
        assertThat(requesterTrail.path("chain").size()).isEqualTo(7);
        assertThat(actions(requesterTrail.path("events"))).contains("agreement:propose", "transaction:initiate",
                "agreement:created", "transaction:completed").doesNotContain("agreement:accept");
        assertThat(requesterTrail.path("events").valueStream().map(event -> event.path("networkId").asString()))
                .containsOnly(world.networkA);
    }

    @Test
    @DisplayName("E2E-SC16-02 and E2E-SC03-02 denied attempts appear in the trail and the audit with their decision, reason and delegation")
    void deniedAttemptsAreAudited() {
        TrustedInteraction world = TrustedInteraction.establish(api(), "\"agreement:propose\"");
        String agreement = world.activeAgreement();
        ApiClient.Response denied = world.agentA.post(transactions(world.networkA), "{\"agreementId\":\""
                + agreement + "\"}");
        assertThat(denied.status()).isEqualTo(403);

        JsonNode audited = world.adminA.get("/api/v1/audit-events?correlationId="
                + denied.json().path("correlationId").asString() + "&result=DENIED").json();
        assertThat(audited.size()).isEqualTo(1);
        JsonNode event = audited.get(0);
        assertThat(event.path("action").asString()).isEqualTo("transaction:initiate");
        assertThat(event.path("reason").asString()).isEqualTo("DELEGATION_SCOPE_VIOLATION");
        assertThat(event.path("delegationId").asString()).isEqualTo(world.delegation);
        JsonNode decision = world.adminA.get("/api/v1/authorization/decisions/" + event.path("decisionId").asString())
                .json();
        assertThat(decision.path("result").asString()).isEqualTo("DENY");
        assertThat(decision.path("reason").asString()).isEqualTo("DELEGATION_SCOPE_VIOLATION");
    }

    @Test
    @DisplayName("E2E-SC16-02 a denied attempt on a transaction appears in its trail")
    void deniedAttemptInTrail() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        String agreement = world.activeAgreement();
        String requested = world.agentA.post(transactions(world.networkA), "{\"agreementId\":\"" + agreement + "\"}")
                .json().path("id").asString();
        IdentityApi identities = world.identities;
        String bobId = identities.human("Bob");
        identities.member(world.networkA, bobId, world.organizationA);
        ApiClient bob = identities.as(identities.actor(bobId), world.networkA);
        assertThat(bob.post(transactions(world.networkA) + "/" + requested + "/cancel", "").status()).isEqualTo(403);

        JsonNode events = world.adminA.get("/api/v1/audit-events/trail?transactionId=" + requested).json()
                .path("events");
        JsonNode deniedEvent = events.valueStream().filter(event -> "DENIED".equals(event.path("result").asString()))
                .findFirst().orElseThrow();
        assertThat(deniedEvent.path("action").asString()).isEqualTo("transaction:initiate");
        assertThat(deniedEvent.path("reason").asString()).isEqualTo("NO_AUTHORITY");
        assertThat(deniedEvent.path("decisionId").asString()).isNotBlank();
    }

    @Test
    @DisplayName("E2E-SC16-03 audit events cannot be modified or deleted through the API")
    void auditIsAppendOnly() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        String id = world.adminA.get("/api/v1/audit-events").json().get(0).path("id").asString();

        assertThat(world.adminA.put("/api/v1/audit-events/" + id, "{}").status()).isEqualTo(405);
        assertThat(world.adminA.delete("/api/v1/audit-events/" + id).status()).isEqualTo(405);
        assertThat(world.adminA.post("/api/v1/audit-events", "{}").status()).isEqualTo(405);
        assertThat(world.adminA.get("/api/v1/audit-events/" + id).status()).isEqualTo(200);
        assertThat(world.humanA.get("/api/v1/audit-events").status()).isEqualTo(403);
    }

    @Test
    @DisplayName("E2E-SC14-01 each transaction transition is audited in both networks")
    void transitionsAreAudited() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        String transaction = world.completedTransaction(world.activeAgreement());

        for (ApiClient admin : List.of(world.adminA, world.adminB)) {
            JsonNode events = admin.get("/api/v1/audit-events?transactionId=" + transaction + "&resourceType=transaction")
                    .json();
            assertThat(actions(events)).contains("transaction:requested", "transaction:authorized",
                    "transaction:executing", "transaction:completed");
        }
    }

    @Test
    @DisplayName("E2E-SC05-02 audit events written before a revocation are unchanged afterwards")
    void historyIsUnchangedByRevocation() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        world.proposed();
        JsonNode before = world.adminA.get("/api/v1/audit-events?delegationId=" + world.delegation).json();
        assertThat(before.size()).isGreaterThan(0);

        transition(world.humanA, world.networkA, world.delegation, "revoke");

        before.valueStream().forEach(event -> assertThat(world.adminA
                .get("/api/v1/audit-events/" + event.path("id").asString()).json()).isEqualTo(event));
    }

    @Test
    @DisplayName("E2E-SC15-10 a spoofed network context is 403 and audited in the identity's network")
    void spoofingIsAudited() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        IdentityApi identities = world.identities;
        String aliceId = identities.human("Alice");
        identities.member(world.networkA, aliceId);
        ApiClient spoof = identities.as(identities.actor(aliceId), world.networkB).withHeader("X-Correlation-Id",
                "spoof-" + aliceId);

        assertThat(spoof.get("/api/v1/principal").status()).isEqualTo(403);

        JsonNode events = world.adminA.get("/api/v1/audit-events?correlationId=spoof-" + aliceId).json();
        assertThat(events.size()).isEqualTo(1);
        assertThat(events.get(0).path("action").asString()).isEqualTo("network:access");
        assertThat(events.get(0).path("result").asString()).isEqualTo("DENIED");
        assertThat(events.get(0).path("identityId").asString()).isEqualTo(aliceId);
        assertThat(events.get(0).path("resourceId").asString()).isEqualTo(world.networkB);
    }

    @Test
    @DisplayName("E2E-SC15-02 audit events of Network B are not found from Network A")
    void auditIsolation() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        String eventOfB = world.adminB.get("/api/v1/audit-events").json().get(0).path("id").asString();

        assertThat(world.adminB.get("/api/v1/audit-events/" + eventOfB).status()).isEqualTo(200);
        assertThat(world.adminA.get("/api/v1/audit-events/" + eventOfB).status()).isEqualTo(404);
    }
}
