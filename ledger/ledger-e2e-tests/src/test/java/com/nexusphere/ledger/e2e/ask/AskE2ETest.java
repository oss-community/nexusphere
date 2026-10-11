package com.nexusphere.ledger.e2e.ask;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.FakeOidcProvider;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AskE2ETest {

    private static PostgreSQLContainer postgres;
    private static FakeOidcProvider oidc;
    private static StandaloneLedger ledger;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
        oidc = FakeOidcProvider.start();
        ledger = StandaloneLedger.start(postgres, freePort(), SigningKeys.generate(),
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

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static long record(LedgerClient client, String agentId, String principalId, String target, String decision,
                       String outcome) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("principalId", principalId);
        body.put("action", "tools/call");
        body.put("target", target);
        body.put("decision", decision);
        body.put("outcome", outcome);
        return client.recordEvidence(body).path("sequence").asLong();
    }

    static List<Long> cited(JsonNode answer) {
        List<Long> sequences = new ArrayList<>();
        answer.path("citations").forEach(citation -> sequences.add(citation.path("sequence").asLong()));
        return sequences;
    }

    private static JsonNode ask(LedgerClient client, String path, String question) {
        LedgerClient.Response response = client.post(path, LedgerClient.json(Map.of("question", question)));
        assertThat(response.status()).isEqualTo(200);
        return response.json();
    }

    @Test
    void anOperatorGetsAnAnswerCitingOnlyVerifiedEntries() {
        String agent = "mailer-" + UUID.randomUUID();
        String alice = "alice-" + UUID.randomUUID();
        long first = record(ledger.client(), agent, alice, "email/send", "ALLOW", "SUCCEEDED");
        long second = record(ledger.client(), agent, alice, "email/send", "ALLOW", "SUCCEEDED");
        long denied = record(ledger.client(), agent, alice, "files/delete", "DENY", "DENIED");
        record(ledger.client(), agent, "bob-" + UUID.randomUUID(), "email/send", "ALLOW", "SUCCEEDED");

        JsonNode answer = ask(ledger.client(), "/api/v1/ask", "What did " + agent + " do for " + alice + "?");

        assertThat(answer.path("model").asString()).isEqualTo("builtin");
        assertThat(answer.path("plan").path("agentId").asString()).isEqualTo(agent);
        assertThat(answer.path("plan").path("principalId").asString()).isEqualTo(alice);
        assertThat(answer.path("considered").asLong()).isEqualTo(3);
        assertThat(cited(answer)).containsExactly(first, second, denied);
        assertThat(answer.path("answer").asString()).startsWith("3 verified entries match the question")
                .doesNotContain("[#");
        assertThat(answer.path("sentences")).hasSize(3);
        assertThat(answer.path("sentences").get(2).path("text").asString()).endsWith("once, denied.");
        assertThat(answer.path("sentences").get(2).path("citations").get(0).asLong()).isEqualTo(denied);
        answer.path("citations").forEach(citation -> {
            assertThat(citation.path("principalId").asString()).isEqualTo(alice);
            assertThat(citation.path("checks").path("content").asBoolean()).isTrue();
            assertThat(citation.path("checks").path("link").asBoolean()).isTrue();
            assertThat(citation.path("checks").path("inclusion").asBoolean()).isTrue();
        });
        assertThat(answer.path("unverified")).isEmpty();
        assertThat(answer.path("checkpointSize").asLong()).isGreaterThanOrEqualTo(denied);
        JsonNode recorded = ledger.client().get("/api/v1/evidence?after=" + (answer.path("recordedAs").asLong() - 1)
                + "&limit=1").json().path("items").get(0);
        assertThat(recorded.path("action").asString()).isEqualTo("ask/question");
        assertThat(recorded.path("agentId").asString()).isEqualTo("ledger");
        assertThat(recorded.path("attributes").path("ask.citations").asString())
                .isEqualTo(first + "," + second + "," + denied);
    }

    @Test
    void theQuestionNarrowsTheSearch() {
        String agent = "filer-" + UUID.randomUUID();
        String carol = "carol-" + UUID.randomUUID();
        record(ledger.client(), agent, carol, "files/read_file", "ALLOW", "SUCCEEDED");
        long denied = record(ledger.client(), agent, carol, "files/delete", "DENY", "DENIED");
        long failed = record(ledger.client(), agent, carol, "files/read_file", "ALLOW", "FAILED");

        JsonNode denials = ask(ledger.client(), "/api/v1/ask", "Which calls of " + agent + " were denied today?");
        JsonNode failures = ask(ledger.client(), "/api/v1/ask", "Show the failed calls for " + carol);
        JsonNode nothing = ask(ledger.client(), "/api/v1/ask", "What did " + agent + " do for nobody-"
                + UUID.randomUUID() + "?");

        assertThat(denials.path("plan").path("decision").asString()).isEqualTo("DENY");
        assertThat(denials.path("plan").path("from").isNull()).isFalse();
        assertThat(cited(denials)).containsExactly(denied);
        assertThat(cited(failures)).containsExactly(failed);
        assertThat(nothing.path("answer").asString()).isEqualTo("No verified evidence matches the question.");
        assertThat(nothing.path("citations")).isEmpty();
    }

    @Test
    void aPrincipalOnlyHearsAboutTheirOwnEvidence() {
        String agent = "helper-" + UUID.randomUUID();
        String dave = "dave-" + UUID.randomUUID();
        String erin = "erin-" + UUID.randomUUID();
        long own = record(ledger.client(), agent, dave, "calendar/create_event", "ALLOW", "SUCCEEDED");
        record(ledger.client(), agent, erin, "calendar/create_event", "ALLOW", "SUCCEEDED");

        JsonNode answer = ask(ledger.client().withApiKey(oidc.token(dave)), "/api/v1/principal/ask",
                "What did " + agent + " do for " + erin + "?");

        assertThat(answer.path("plan").path("principalId").asString()).isEqualTo(dave);
        assertThat(cited(answer)).containsExactly(own);
        assertThat(ledger.client().post("/api/v1/principal/ask", LedgerClient.json(Map.of("question", "hi")))
                .status()).isEqualTo(403);
        assertThat(ledger.client().withApiKey(oidc.token(dave)).post("/api/v1/ask",
                LedgerClient.json(Map.of("question", "hi"))).status()).isEqualTo(403);
    }

    @Test
    void anEntryThatNoLongerMatchesItsHashIsNeverCited() throws Exception {
        String agent = "payer-" + UUID.randomUUID();
        String frank = "frank-" + UUID.randomUUID();
        long kept = record(ledger.client(), agent, frank, "payments/refund", "ALLOW", "SUCCEEDED");
        long tampered = record(ledger.client(), agent, frank, "payments/refund", "ALLOW", "SUCCEEDED");
        Container.ExecResult result = postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d",
                postgres.getDatabaseName(), "-v", "ON_ERROR_STOP=1", "-c",
                "alter table ledger.evidence_record disable trigger evidence_record_append_only;"
                        + " update ledger.evidence_record set outcome = 'FAILED' where sequence = " + tampered + ";"
                        + " alter table ledger.evidence_record enable trigger evidence_record_append_only;");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();

        JsonNode answer = ask(ledger.client(), "/api/v1/ask", "What did " + agent + " do for " + frank + "?");

        assertThat(answer.path("considered").asLong()).isEqualTo(2);
        assertThat(cited(answer)).containsExactly(kept);
        assertThat(answer.path("unverified").get(0).asLong()).isEqualTo(tampered);
        answer.path("sentences").forEach(sentence -> assertThat(sentence.path("citations").toString())
                .doesNotContain(String.valueOf(tampered)));
    }

    @Test
    void aQuestionMustBeShort() {
        assertThat(ledger.client().post("/api/v1/ask", LedgerClient.json(Map.of("question", " "))).status())
                .isEqualTo(400);
        assertThat(ledger.client().post("/api/v1/ask", LedgerClient.json(Map.of("question", "x".repeat(501))))
                .status()).isEqualTo(400);
    }
}
