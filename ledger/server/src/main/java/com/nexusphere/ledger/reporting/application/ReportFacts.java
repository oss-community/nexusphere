package com.nexusphere.ledger.reporting.application;

import com.nexusphere.ledger.compliance.application.Compliance;
import com.nexusphere.ledger.verifier.PackageReport;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

public record ReportFacts(List<JsonNode> entries, long links, PackageReport verification, Compliance compliance) {

    private static final LocalDate REFERENCE = LocalDate.of(2000, 1, 1);

    public static ReportFacts of(JsonNode pkg, PackageReport verification, Compliance compliance) {
        List<JsonNode> entries = new ArrayList<>();
        long links = 0;
        for (JsonNode link : pkg.path("links")) {
            links++;
            if (link.path("entry").isObject()) {
                entries.add(link.path("entry"));
            }
        }
        return new ReportFacts(List.copyOf(entries), links, verification, compliance);
    }

    public int total() {
        return entries.size();
    }

    public List<Long> sequences(Predicate<JsonNode> filter) {
        return entries.stream().filter(filter).map(entry -> entry.path("sequence").asLong()).toList();
    }

    public long count(Predicate<JsonNode> filter) {
        return entries.stream().filter(filter).count();
    }

    public static Predicate<JsonNode> has(String field) {
        return entry -> entry.hasNonNull(field) && !entry.path(field).asString("").isEmpty();
    }

    public static Predicate<JsonNode> is(String field, String value) {
        return entry -> value.equals(entry.path(field).asString(null));
    }

    public static Predicate<JsonNode> model(String field) {
        return entry -> !entry.path("model").path(field).asString("").isEmpty();
    }

    public Set<String> models() {
        Set<String> models = new TreeSet<>();
        entries.stream().filter(model("name")).forEach(entry -> {
            JsonNode model = entry.path("model");
            models.add(model.path("name").asString() + (model.hasNonNull("version")
                    ? " " + model.path("version").asString() : ""));
        });
        return models;
    }

    public Set<String> modelValues(String field) {
        Set<String> values = new TreeSet<>();
        entries.stream().filter(model(field)).forEach(entry -> values.add(entry.path("model").path(field).asString()));
        return values;
    }

    public static Predicate<JsonNode> erased() {
        return entry -> entry.path("erased").asBoolean(false);
    }

    public Set<String> values(String field) {
        Set<String> values = new TreeSet<>();
        entries.forEach(entry -> {
            if (entry.hasNonNull(field)) {
                values.add(entry.path(field).asString());
            }
        });
        return values;
    }

    public Set<String> services() {
        Set<String> services = new TreeSet<>();
        for (String target : values("target")) {
            int slash = target.indexOf('/');
            services.add(slash > 0 ? target.substring(0, slash) : target);
        }
        return services;
    }

    public String first() {
        return entries.isEmpty() ? null : entries.getFirst().path("occurredAt").asString();
    }

    public String last() {
        return entries.isEmpty() ? null : entries.getLast().path("occurredAt").asString();
    }

    public boolean anchored() {
        return verification.timestampedAt() != null || verification.rekorLogIndex() != null
                || !verification.witnesses().isEmpty();
    }

    public Optional<Period> minimum(String action) {
        return compliance.retention(action);
    }

    public Optional<Period> maximum(String action) {
        return compliance.maximum(action);
    }

    public static boolean atLeast(Period period, Period required) {
        return !REFERENCE.plus(period).isBefore(REFERENCE.plus(required));
    }
}
