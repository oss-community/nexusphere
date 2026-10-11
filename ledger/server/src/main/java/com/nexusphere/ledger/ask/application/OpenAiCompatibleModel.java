package com.nexusphere.ledger.ask.application;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.Timestamps;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class OpenAiCompatibleModel implements LanguageModel {

    static final String PLAN_PROMPT = """
            You turn a question about the evidence of AI agent actions into a search. Answer with one JSON object and \
            nothing else, with these keys, each a string or null: agentId, principalId, action, target, decision, \
            outcome, from, to. agentId and action must be one of the known values or null. target is a word the \
            target must contain. decision is ALLOW or DENY. outcome is SUCCEEDED, FAILED, DENIED or PENDING. from and \
            to are ISO-8601 UTC instants, from inclusive and to exclusive, computed from the current time. Use null \
            for anything the question does not ask about.""";

    static final String ANSWER_PROMPT = """
            You answer a question about AI agent actions using only the evidence lines given, each starting with its \
            citation such as [#12]. Every sentence must end with the citations of the evidence lines it relies on, \
            for example: invoice-agent sent two emails for alice [#12][#14]. Do not use any knowledge beyond the \
            evidence lines, do not guess and do not cite a line that does not support the sentence. If the evidence \
            does not answer the question, answer exactly: No verified evidence answers the question.""";

    private static final Set<String> DECISIONS = Set.of("ALLOW", "DENY");
    private static final Set<String> OUTCOMES = Set.of("SUCCEEDED", "FAILED", "DENIED", "PENDING");

    private final URI url;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public OpenAiCompatibleModel(URI url, String model, String apiKey, Duration timeout) {
        this.url = URI.create(url.toString().replaceAll("/+$", "") + "/chat/completions");
        this.model = model;
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public QueryPlan plan(String question, PlanContext context) {
        String user = "Current time: " + Timestamps.format(context.now()) + "\nKnown agents: "
                + String.join(", ", context.agents()) + "\nKnown actions: " + String.join(", ", context.actions())
                + "\nQuestion: " + question;
        String content = complete(PLAN_PROMPT, user, true);
        JsonNode node;
        try {
            node = json.readTree(extractObject(content));
        } catch (JacksonException | IllegalArgumentException e) {
            throw new ModelException("The model did not answer with a JSON search: " + e.getMessage());
        }
        String agent = known(text(node, "agentId"), context.agents());
        String action = known(text(node, "action"), context.actions());
        String decision = upper(text(node, "decision"), DECISIONS);
        String outcome = upper(text(node, "outcome"), OUTCOMES);
        return new QueryPlan(agent, text(node, "principalId"), action, text(node, "target"), decision, outcome,
                instant(text(node, "from")), instant(text(node, "to")));
    }

    @Override
    public String answer(String question, QueryPlan plan, List<EvidenceEntry> evidence) {
        StringBuilder user = new StringBuilder("Evidence lines:\n");
        evidence.forEach(entry -> user.append(line(entry)).append('\n'));
        user.append("Question: ").append(question);
        return complete(ANSWER_PROMPT, user.toString(), false);
    }

    static String line(EvidenceEntry entry) {
        return Citations.cite(entry.sequence()) + " " + Timestamps.format(entry.occurredAt()) + " agent="
                + entry.agentId() + " principal=" + (entry.erased() ? "(erased)" : entry.principalId()) + " action="
                + entry.action() + " target=" + entry.target() + " decision=" + entry.decision() + " outcome="
                + entry.outcome() + (entry.model() == null || entry.model().name() == null ? ""
                : " model=" + entry.model().name());
    }

    private String complete(String system, String user, boolean jsonObject) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", user));
        body.put("messages", messages);
        if (jsonObject) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ModelException("The model at " + url + " cannot be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("Interrupted while asking the model");
        }
        if (response.statusCode() / 100 != 2) {
            throw new ModelException("The model at " + url + " answered " + response.statusCode());
        }
        try {
            JsonNode content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isString()) {
                throw new ModelException("The model answered without a message");
            }
            return content.asString();
        } catch (JacksonException e) {
            throw new ModelException("The model answered something that is not JSON");
        }
    }

    static String extractObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("no JSON object");
        }
        return content.substring(start, end + 1);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() && !"null".equals(value.asString())
                ? value.asString().strip() : null;
    }

    private static String known(String value, List<String> known) {
        return value == null ? null : known.stream().filter(value::equalsIgnoreCase).findFirst().orElse(null);
    }

    private static String upper(String value, Set<String> allowed) {
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        return allowed.contains(upper) ? upper : null;
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
