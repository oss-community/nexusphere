package com.nexusphere.ledger.compliance.domain.model;

import com.nexusphere.ledger.chain.CanonicalJson;
import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.GrantTerms;
import com.nexusphere.ledger.chain.Hashes;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record ComplianceProfile(String id, int version, String name, String description, List<Retention> retention,
                                Erasure erasure, List<String> residency, List<String> requiredFields,
                                List<String> timestampAuthorities, List<String> reports) {

    public static final Set<String> FIELDS = Set.of("target", "reason", "delegationId", "inputHash", "outputHash",
            "correlationId", "occurredAt");
    public static final String ATTRIBUTE_FIELD = "attributes.";

    private static final Set<String> KEYS = Set.of("id", "version", "name", "description", "retention", "erasure",
            "residency", "requiredFields", "timestampAuthorities", "reports");
    private static final Set<String> RETENTION_KEYS = Set.of("actions", "minimum", "maximum");
    private static final LocalDate REFERENCE = LocalDate.of(2000, 1, 1);
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern REGION = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern REPORT = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern ATTRIBUTE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public ComplianceProfile {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id must match " + ID.pattern());
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        description = description == null ? "" : description;
        retention = List.copyOf(retention == null ? List.of() : retention);
        erasure = erasure == null ? new Erasure(false, null) : erasure;
        residency = List.copyOf(residency == null ? List.of() : residency.stream()
                .map(region -> region.toLowerCase(Locale.ROOT)).distinct().sorted().toList());
        residency.forEach(region -> check(REGION, region, "residency"));
        requiredFields = List.copyOf(requiredFields == null ? List.of() : requiredFields.stream().distinct().sorted()
                .toList());
        requiredFields.forEach(ComplianceProfile::checkField);
        timestampAuthorities = List.copyOf(timestampAuthorities == null ? List.of() : timestampAuthorities.stream()
                .distinct().sorted().toList());
        timestampAuthorities.forEach(ComplianceProfile::checkAuthority);
        reports = List.copyOf(reports == null ? List.of() : reports.stream().distinct().sorted().toList());
        reports.forEach(report -> check(REPORT, report, "reports"));
    }

    public static ComplianceProfile parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("a profile must be a JSON object");
        }
        for (String key : node.propertyNames()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown field " + key);
            }
        }
        if (!node.path("version").isInt()) {
            throw new IllegalArgumentException("version must be a number");
        }
        List<Retention> retention = new ArrayList<>();
        for (JsonNode rule : array(node, "retention")) {
            for (String key : rule.propertyNames()) {
                if (!RETENTION_KEYS.contains(key)) {
                    throw new IllegalArgumentException("unknown field retention." + key);
                }
            }
            retention.add(new Retention(text(rule, "actions"), period(text(rule, "minimum"), "retention.minimum"),
                    rule.hasNonNull("maximum") ? period(text(rule, "maximum"), "retention.maximum") : null));
        }
        JsonNode erasure = node.path("erasure");
        Erasure erasureRule = erasure.isMissingNode() ? null : new Erasure(erasure.path("onRequest").asBoolean(false),
                erasure.hasNonNull("deadline") ? period(text(erasure, "deadline"), "erasure.deadline") : null);
        return new ComplianceProfile(text(node, "id"), node.path("version").intValue(), text(node, "name"),
                node.path("description").asString(""), retention, erasureRule, texts(node, "residency"),
                texts(node, "requiredFields"), texts(node, "timestampAuthorities"), texts(node, "reports"));
    }

    public Map<String, Object> definition() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("version", version);
        map.put("name", name);
        map.put("description", description);
        map.put("retention", retention.stream().map(Retention::toMap).toList());
        map.put("erasure", erasure.toMap());
        map.put("residency", residency);
        map.put("requiredFields", requiredFields);
        map.put("timestampAuthorities", timestampAuthorities);
        map.put("reports", reports);
        return map;
    }

    public String digest() {
        return Hashes.sha256(CanonicalJson.bytes(definition()));
    }

    public Checkpoint.Profile reference() {
        return new Checkpoint.Profile(id, digest());
    }

    public record Retention(String actions, Period minimum, Period maximum) {

        public Retention {
            if (actions == null || actions.isBlank()) {
                throw new IllegalArgumentException("retention.actions is required");
            }
            if (minimum == null || minimum.isNegative()) {
                throw new IllegalArgumentException("retention.minimum must be a positive ISO-8601 period");
            }
            if (maximum != null && (maximum.isNegative() || maximum.isZero()
                    || REFERENCE.plus(maximum).isBefore(REFERENCE.plus(minimum)))) {
                throw new IllegalArgumentException("retention.maximum must be a period no shorter than the minimum");
            }
        }

        public Retention(String actions, Period minimum) {
            this(actions, minimum, null);
        }

        public boolean covers(String action) {
            return GrantTerms.matches(actions, action);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("actions", actions);
            map.put("minimum", minimum.toString());
            if (maximum != null) {
                map.put("maximum", maximum.toString());
            }
            return map;
        }
    }

    public record Erasure(boolean onRequest, Period deadline) {

        public Erasure {
            if (deadline != null && (deadline.isNegative() || deadline.isZero())) {
                throw new IllegalArgumentException("erasure.deadline must be a positive ISO-8601 period");
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("onRequest", onRequest);
            if (deadline != null) {
                map.put("deadline", deadline.toString());
            }
            return map;
        }
    }

    private static void checkField(String field) {
        if (field.startsWith(ATTRIBUTE_FIELD)) {
            check(ATTRIBUTE, field.substring(ATTRIBUTE_FIELD.length()), "requiredFields");
        } else if (!FIELDS.contains(field)) {
            throw new IllegalArgumentException("requiredFields may only name " + FIELDS.stream().sorted().toList()
                    + " or " + ATTRIBUTE_FIELD + "<name>, not " + field);
        }
    }

    private static void checkAuthority(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("timestampAuthorities must be http(s) URLs, not " + url);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("timestampAuthorities must be http(s) URLs, not " + url);
        }
    }

    private static void check(Pattern pattern, String value, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " entries must match " + pattern.pattern() + ", not " + value);
        }
    }

    private static Period period(String text, String field) {
        try {
            return Period.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 period such as P6M, not " + text);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.asString();
    }

    private static Iterable<JsonNode> array(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return value;
    }

    private static List<String> texts(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array(node, field)) {
            if (!value.isString()) {
                throw new IllegalArgumentException(field + " must be an array of strings");
            }
            values.add(value.asString());
        }
        return values;
    }
}
