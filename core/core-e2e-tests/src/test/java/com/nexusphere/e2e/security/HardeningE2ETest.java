package com.nexusphere.e2e.security;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.IdentityApi;
import com.nexusphere.e2e.support.TrustedInteraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.nexusphere.e2e.support.DelegationApi.assign;
import static com.nexusphere.e2e.support.DelegationApi.evaluate;
import static com.nexusphere.e2e.support.DelegationApi.grant;
import static com.nexusphere.e2e.support.DelegationApi.principalId;
import static org.assertj.core.api.Assertions.assertThat;

class HardeningE2ETest extends E2ETestBase {

    private static final String CORRELATION = "X-Correlation-Id";

    private TrustedInteraction world;
    private IdentityApi identities;

    @BeforeEach
    void world() {
        world = TrustedInteraction.establish(api());
        identities = world.identities;
    }

    private static void assertError(ApiClient.Response response, int status, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.json().path("code").asString()).isEqualTo(code);
    }

    private static String credentials(String identityId) {
        return "/api/v1/identities/" + identityId + "/credentials";
    }

    private static List<String> actions(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json().valueStream().map(event -> event.path("action").asString()).toList();
    }

    @Test
    @DisplayName("E2E-SC19-01 role assignment, trust and federation management cannot be delegated")
    void managementAuthorityIsNotDelegable() {
        for (String action : List.of("role:assign", "trust:manage", "federation:manage")) {
            assertError(grant(world.adminA, world.networkA, world.humanAPrincipal, "\"" + action + "\"", null, null),
                    422, "ACTION_NOT_DELEGABLE");
        }
        assertError(grant(world.adminA, world.networkA, world.humanAPrincipal,
                "\"agreement:propose\",\"role:assign\"", null, null), 422, "ACTION_NOT_DELEGABLE");
    }

    @Test
    @DisplayName("E2E-SC19-02 a principal cannot assign a role to itself")
    void selfAssignmentIsRejected() {
        assertError(assign(world.adminA, world.networkA, principalId(world.adminA), "AUDITOR"), 403,
                "ROLE_SELF_ASSIGNMENT");
        assertError(assign(world.humanA, world.networkA, world.humanAPrincipal, "NETWORK_ADMINISTRATOR"), 403,
                "NO_AUTHORITY");
    }

    @Test
    @DisplayName("E2E-SC19-03 a suspended network blocks every authorized change but keeps reads and audit")
    void suspendedNetworkIsBlockedCentrally() {
        assertThat(world.sovereignty.transition(world.networkA, "suspend").status()).isEqualTo(200);

        JsonNode decision = evaluate(world.humanA, "agreement:propose", world.networkB, world.cnc, null);
        assertThat(decision.path("result").asString()).isEqualTo("DENY");
        assertThat(decision.path("reason").asString()).isEqualTo("NETWORK_NOT_ACTIVE");
        assertError(assign(world.adminA, world.networkA, world.agentAPrincipal, "AUDITOR"), 409,
                "NETWORK_NOT_ACTIVE");
        assertError(world.draft(world.humanA, world.capability, "{}"), 409, "NETWORK_NOT_ACTIVE");
        assertThat(evaluate(world.humanA, "capability:discover", world.networkA, null, null).path("result")
                .asString()).isEqualTo("ALLOW");
        assertThat(world.adminA.get("/api/v1/audit-events").status()).isEqualTo(200);

        assertThat(world.sovereignty.transition(world.networkA, "activate").status()).isEqualTo(200);
        assertThat(evaluate(world.humanA, "agreement:propose", world.networkB, world.cnc, null).path("result")
                .asString()).isEqualTo("ALLOW");
    }

    @Test
    @DisplayName("E2E-SC19-04 credentials expire, can be revoked, and rotation invalidates old secrets and tokens")
    void credentialLifecycle() {
        String carol = identities.human("Carol");
        identities.member(world.networkA, carol, world.organizationA);
        String oldSecret = identities.secret(carol);
        ApiClient oldToken = identities.as(identities.actor(carol), world.networkA);
        assertThat(oldToken.get("/api/v1/principal").status()).isEqualTo(200);

        ApiClient.Response rotated = oldToken.post(credentials(carol) + "/rotate", "");
        assertThat(rotated.status()).as(rotated.body()).isEqualTo(201);
        assertThat(rotated.json().path("status").asString()).isEqualTo("ACTIVE");
        String newSecret = rotated.json().path("secret").asString();
        String newCredential = rotated.json().path("credentialId").asString();

        assertError(oldToken.get("/api/v1/principal"), 401, "INVALID_TOKEN");
        assertError(identities.token(carol, oldSecret), 401, "INVALID_CREDENTIALS");
        ApiClient.Response token = identities.token(carol, newSecret);
        assertThat(token.status()).isEqualTo(200);
        ApiClient current = identities.as(new IdentityApi.Actor(carol, token.json().path("accessToken").asString()),
                world.networkA);
        assertThat(current.get("/api/v1/principal").status()).isEqualTo(200);

        JsonNode listed = current.get(credentials(carol)).json();
        assertThat(listed.valueStream().map(credential -> credential.path("status").asString()))
                .containsExactly("REVOKED", "REVOKED", "ACTIVE");
        assertThat(listed.valueStream().noneMatch(credential -> credential.has("secret"))).isTrue();

        ApiClient operator = api().asOperator();
        assertThat(operator.post(credentials(carol) + "/" + newCredential + "/revoke", "").json().path("status")
                .asString()).isEqualTo("REVOKED");
        assertError(current.get("/api/v1/principal"), 401, "INVALID_TOKEN");
        assertError(identities.token(carol, newSecret), 401, "INVALID_CREDENTIALS");
        assertError(operator.post(credentials(carol) + "/" + newCredential + "/revoke", ""), 409,
                "CREDENTIAL_ALREADY_REVOKED");

        assertError(operator.post(credentials(carol), "{\"expiresAt\":\"" + Instant.now().minusSeconds(60) + "\"}"),
                400, "CREDENTIAL_EXPIRY_IN_PAST");
        assertError(operator.post(credentials(carol),
                "{\"expiresAt\":\"" + Instant.now().plus(Duration.ofDays(400)) + "\"}"), 400,
                "CREDENTIAL_EXPIRY_TOO_LONG");
        Instant expiry = Instant.now().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.SECONDS);
        ApiClient.Response limited = operator.post(credentials(carol), "{\"expiresAt\":\"" + expiry + "\"}");
        assertThat(limited.status()).isEqualTo(201);
        assertThat(Instant.parse(limited.json().path("expiresAt").asString())).isEqualTo(expiry);
        assertError(world.humanA.post(credentials(carol), ""), 403, "PLATFORM_OPERATOR_REQUIRED");
    }

    @Test
    @DisplayName("E2E-SC19-05 identity, credential and role changes are audited in the platform and network streams")
    void identityChangesAreAudited() {
        String dave = identities.human("Dave");
        identities.member(world.networkA, dave, world.organizationA);
        String secret = identities.secret(dave);
        ApiClient operator = api().asOperator();
        assertThat(operator.post("/api/v1/identities/" + dave + "/suspend", "").status()).isEqualTo(200);
        assertThat(identities.token(dave, secret).status()).isEqualTo(401);

        assertThat(actions(operator.get("/api/v1/audit-events/platform?resourceId=" + dave)))
                .contains("identity:create", "identity:suspend");
        assertThat(actions(world.adminA.get("/api/v1/audit-events?resourceId=" + dave)))
                .contains("identity:suspend").doesNotContain("identity:create");
        assertThat(actions(world.adminA.get("/api/v1/audit-events?resourceType=credential")))
                .contains("credential:issue");
        assertThat(actions(world.adminB.get("/api/v1/audit-events?resourceId=" + dave))).isEmpty();
        assertThat(world.adminA.get("/api/v1/audit-events?resourceId=" + world.humanAPrincipal).json().valueStream()
                .filter(event -> event.path("eventType").asString().equals("authorization.RoleAssigned"))
                .map(event -> event.path("metadata").path("role").asString()))
                .containsExactlyInAnyOrder("AGREEMENT_MANAGER", "TRANSACTION_OPERATOR");
        assertError(world.adminA.get("/api/v1/audit-events/platform"), 403, "PLATFORM_OPERATOR_REQUIRED");
    }

    @Test
    @DisplayName("E2E-SC19-06 every endpoint except sign-in, health, platform info and API docs needs a token")
    void endpointsAreDeniedByDefault() {
        ApiClient anonymous = api();

        for (String path : List.of("/api/v1/principal", "/api/v1/authorization/roles", "/api/v1/capability-types",
                "/api/v1/networks/" + world.networkA, "/api/v1/audit-events/platform", "/api/v1/does-not-exist")) {
            assertError(anonymous.get(path), 401, "AUTHENTICATION_REQUIRED");
        }
        for (String path : List.of("/api/v1/platform", "/actuator/health", "/v3/api-docs")) {
            assertThat(anonymous.get(path).status()).as(path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("E2E-SC19-07 the longest accepted correlation id is kept in the audit trail")
    void longCorrelationIdsAreAudited() {
        String correlation = "c".repeat(128);
        ApiClient.Response response = world.humanA.withHeader(CORRELATION, correlation)
                .post("/api/v1/authorization/evaluate", "{\"action\":\"agreement:propose\",\"resource\":{"
                        + "\"type\":\"agreement\",\"id\":\"draft\",\"networkId\":\"" + world.networkA + "\"}}");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header(CORRELATION)).contains(correlation);
        assertThat(world.adminA.get("/api/v1/audit-events?correlationId=" + correlation).json().valueStream()
                .map(event -> event.path("correlationId").asString())).containsOnly(correlation).isNotEmpty();
    }

    @Test
    @DisplayName("E2E-SC19-08 more concurrent authorized requests than request threads never exhaust the pool")
    void concurrentRequestsDoNotExhaustThePool() throws Exception {
        String body = "{\"action\":\"agreement:propose\",\"resource\":{\"type\":\"agreement\",\"id\":\"draft\","
                + "\"networkId\":\"" + world.networkB + "\"},\"capabilityTypeCode\":\"" + world.cnc + "\"}";
        try (ExecutorService executor = Executors.newFixedThreadPool(48)) {
            List<Future<Integer>> calls = IntStream.range(0, 96)
                    .mapToObj(i -> executor.submit(() -> world.humanA.post("/api/v1/authorization/evaluate", body)
                            .status()))
                    .toList();
            for (Future<Integer> call : calls) {
                assertThat(call.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }
    }
}
