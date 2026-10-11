package com.nexusphere.ledger.e2e.principal;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.FakeOidcProvider;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrincipalDashboardE2ETest {

    private static final KeyPair KEYS = SigningKeys.generate();

    private static PostgreSQLContainer postgres;
    private static FakeOidcProvider oidc;
    private static StandaloneLedger ledger;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
        oidc = FakeOidcProvider.start();
        ledger = StandaloneLedger.start(postgres, freePort(), KEYS,
                "--ledger.oidc.issuer=" + oidc.issuer(),
                "--ledger.oidc.audience=" + FakeOidcProvider.AUDIENCE,
                "--ledger.oidc.principal-claim=preferred_username");
    }

    @AfterAll
    static void stop() {
        ledger.close();
        oidc.close();
        postgres.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static LedgerClient as(String principal) {
        return ledger.client().withApiKey(oidc.token(principal));
    }

    private static void record(String agentId, String principalId, String decision, String outcome) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("principalId", principalId);
        body.put("action", "tools/call");
        body.put("target", "files/read_file");
        body.put("decision", decision);
        body.put("outcome", outcome);
        ledger.client().recordEvidence(body);
    }

    @Test
    void aPrincipalSeesTheActivityOfAgentsActingForThemOnly() {
        String alice = "alice-" + UUID.randomUUID();
        String agent = "agent-" + UUID.randomUUID();
        record(agent, alice, "ALLOW", "SUCCEEDED");
        record(agent, alice, "ALLOW", "FAILED");
        record(agent, alice, "DENY", "DENIED");
        record(agent, "bob-" + UUID.randomUUID(), "ALLOW", "SUCCEEDED");

        JsonNode activity = as(alice).get("/api/v1/principal/activity?days=7").json();

        assertThat(activity.path("total").asLong()).isEqualTo(3);
        assertThat(activity.path("allowed").asLong()).isEqualTo(2);
        assertThat(activity.path("denied").asLong()).isEqualTo(1);
        assertThat(activity.path("failed").asLong()).isEqualTo(1);
        assertThat(activity.path("days")).hasSize(1);
        assertThat(activity.path("days").get(0).path("day").asString())
                .isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat(activity.path("agents")).hasSize(1);
        assertThat(activity.path("agents").get(0).path("agentId").asString()).isEqualTo(agent);
        JsonNode recent = as(alice).get("/api/v1/principal/evidence/recent?limit=2").json();
        JsonNode older = as(alice).get("/api/v1/principal/evidence/recent?limit=2&before="
                + recent.path("nextBefore").asLong()).json();
        assertThat(recent.path("items")).hasSize(2);
        assertThat(recent.path("items").get(0).path("decision").asString()).isEqualTo("DENY");
        assertThat(recent.path("items").get(0).path("sequence").asLong())
                .isGreaterThan(recent.path("items").get(1).path("sequence").asLong());
        assertThat(older.path("items")).hasSize(1);
        assertThat(older.path("nextBefore").isNull()).isTrue();
        assertThat(as(alice).get("/api/v1/principal/activity?days=0").status()).isEqualTo(400);
        assertThat(ledger.client().get("/api/v1/principal/activity").status()).isEqualTo(403);
    }

    @Test
    void aPrincipalExportsAPackageOfTheirOwnEvidence() {
        String carol = "carol-" + UUID.randomUUID();
        String agent = "agent-" + UUID.randomUUID();
        record(agent, carol, "ALLOW", "SUCCEEDED");
        record(agent, "dave-" + UUID.randomUUID(), "ALLOW", "SUCCEEDED");
        record(agent, carol, "DENY", "DENIED");

        LedgerClient.Response response = as(carol).post("/api/v1/principal/packages",
                LedgerClient.json(Map.of("principalId", "dave", "includePrompts", true)));
        JsonNode pkg = response.json();
        List<String> principals = new ArrayList<>();
        pkg.path("links").forEach(link -> {
            if (link.path("entry").isObject()) {
                principals.add(link.path("entry").path("principalId").asString());
            }
        });
        PackageReport report = PackageVerifier.verify(pkg, SigningKeys.encode(KEYS.getPublic()));

        assertThat(response.status()).isEqualTo(200);
        assertThat(pkg.path("scope").path("principalId").asString()).isEqualTo(carol);
        assertThat(principals).containsExactly(carol, carol);
        assertThat(pkg.has("prompts")).isFalse();
        assertThat(report.problems()).isEmpty();
        assertThat(report.valid()).isTrue();
    }
}
