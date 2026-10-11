package com.nexusphere.ledger.e2e.ask;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.FakeLanguageModel;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AskOpenAiCompatibleE2ETest {

    private static final Pattern LINE = Pattern.compile("\\[#(\\d+)] \\S+ agent=(\\S+) principal=(\\S+)");

    private static PostgreSQLContainer postgres;
    private static FakeLanguageModel model;
    private static StandaloneLedger ledger;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
        model = FakeLanguageModel.start();
        ledger = StandaloneLedger.start(postgres, AskE2ETest.freePort(), SigningKeys.generate(),
                "--ledger.ask.model=openai",
                "--ledger.ask.url=" + model.url(),
                "--ledger.ask.name=qwen2.5:7b",
                "--ledger.ask.api-key=model-key");
    }

    @AfterAll
    static void stop() {
        ledger.close();
        model.close();
        postgres.stop();
    }

    @Test
    void theModelPlansTheSearchAndOnlyCitedSentencesSurvive() {
        String agent = "mailer-" + UUID.randomUUID();
        String alice = "alice-" + UUID.randomUUID();
        long sent = AskE2ETest.record(ledger.client(), agent, alice, "email/send", "ALLOW", "SUCCEEDED");
        long denied = AskE2ETest.record(ledger.client(), agent, alice, "files/delete", "DENY", "DENIED");
        AskE2ETest.record(ledger.client(), agent, "bob-" + UUID.randomUUID(), "email/send", "ALLOW", "SUCCEEDED");
        model.failWith(200);
        model.plans(request -> {
            Map<String, Object> plan = new HashMap<>();
            plan.put("agentId", agent.toUpperCase());
            plan.put("principalId", alice);
            plan.put("action", "no/such-action");
            plan.put("decision", "deny");
            plan.put("outcome", null);
            plan.put("from", "2020-01-01");
            return "Here is the search: " + FakeLanguageModel.json(plan);
        });
        model.answers(request -> {
            Matcher line = LINE.matcher(request.user());
            StringBuilder answer = new StringBuilder();
            while (line.find()) {
                answer.append(line.group(2)).append(" was denied a call for ").append(line.group(3))
                        .append(" [#").append(line.group(1)).append("]. ");
            }
            return answer + "It also wired money to a stranger [#999999]. Nothing else happened.";
        });

        JsonNode answer = ledger.client().post("/api/v1/ask", LedgerClient.json(Map.of("question",
                "Which calls did the mailer have denied for alice?"))).json();

        FakeLanguageModel.Request planning = model.requests().get(model.requests().size() - 2);
        FakeLanguageModel.Request answering = model.requests().getLast();
        assertThat(planning.wantsJson()).isTrue();
        assertThat(planning.authorization()).isEqualTo("Bearer model-key");
        assertThat(planning.body().path("model").asString()).isEqualTo("qwen2.5:7b");
        assertThat(planning.body().path("temperature").asInt()).isZero();
        assertThat(planning.user()).contains("Known agents: ").contains(agent);
        assertThat(answering.user()).contains("[#" + denied + "]").doesNotContain("[#" + sent + "]")
                .doesNotContain("bob-");
        assertThat(answer.path("model").asString()).isEqualTo("qwen2.5:7b");
        assertThat(answer.path("plan").path("agentId").asString()).isEqualTo(agent);
        assertThat(answer.path("plan").path("action").isNull()).isTrue();
        assertThat(answer.path("plan").path("decision").asString()).isEqualTo("DENY");
        assertThat(answer.path("answer").asString()).isEqualTo(agent + " was denied a call for " + alice + ".");
        assertThat(answer.path("droppedSentences").asInt()).isEqualTo(2);
        assertThat(AskE2ETest.cited(answer)).containsExactly(denied);
    }

    @Test
    void anAnswerWithoutValidCitationsIsWithheld() {
        String agent = "writer-" + UUID.randomUUID();
        AskE2ETest.record(ledger.client(), agent, "carol-" + UUID.randomUUID(), "docs/write", "ALLOW", "SUCCEEDED");
        model.failWith(200);
        model.plans(request -> FakeLanguageModel.json(Map.of("agentId", agent)));
        model.answers(request -> "The agent did great work and everyone was happy.");

        JsonNode answer = ledger.client().post("/api/v1/ask", LedgerClient.json(Map.of("question",
                "How did the writer do?"))).json();

        assertThat(answer.path("answer").asString())
                .isEqualTo("The model's answer could not be tied to the evidence, so it is not shown.");
        assertThat(answer.path("citations")).isEmpty();
        assertThat(answer.path("droppedSentences").asInt()).isEqualTo(1);
    }

    @Test
    void anUnavailableModelIsABadGateway() {
        model.failWith(503);
        try {
            LedgerClient.Response response = ledger.client().post("/api/v1/ask", LedgerClient.json(Map.of(
                    "question", "What happened today?")));

            assertThat(response.status()).isEqualTo(502);
            assertThat(response.json().path("code").asString()).isEqualTo("MODEL_UNAVAILABLE");
        } finally {
            model.failWith(200);
        }
    }

    @Test
    void aModelThatAnswersWithoutJsonIsABadGateway() {
        model.failWith(200);
        model.plans(request -> "I cannot help with that.");

        LedgerClient.Response response = ledger.client().post("/api/v1/ask", LedgerClient.json(Map.of(
                "question", "What happened today?")));

        assertThat(response.status()).isEqualTo(502);
    }
}
