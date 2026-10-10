package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.CanonicalJson;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.SigningKeys;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public final class ReportVerifier {

    public static final String FORMAT = "nexusphere-ledger/report/v1";
    public static final Set<String> STATUSES = Set.of("SHOWN", "PARTLY_SHOWN", "NOT_SHOWN");

    private ReportVerifier() {
    }

    public record ReportCheck(boolean valid, String report, String title, String keyId, Long checkpointSequence,
                              Long entries, Map<String, Integer> statuses, List<String> problems) {
    }

    public static byte[] signedBytes(JsonNode report) {
        Map<String, Object> map = object(report);
        map.remove("signature");
        return CanonicalJson.bytes(map);
    }

    public static String entriesDigest(List<String> hashes) {
        return Hashes.sha256(CanonicalJson.bytes(Map.of("hashes", hashes)));
    }

    public static ReportCheck verify(JsonNode report, JsonNode pkg, String pinnedPublicKey, JsonNode keyList) {
        List<String> problems = new ArrayList<>();
        String id = report.path("report").asString(null);
        String title = report.path("title").asString(null);
        String keyId = report.path("keyId").asString(null);
        JsonNode evidence = report.path("evidence");
        Long sequence = evidence.path("checkpoint").isIntegralNumber() ? evidence.path("checkpoint").asLong() : null;
        Long entries = evidence.path("entries").isIntegralNumber() ? evidence.path("entries").asLong() : null;
        Map<String, Integer> statuses = new TreeMap<>();
        for (JsonNode section : report.path("sections")) {
            String status = section.path("status").asString("");
            if (!STATUSES.contains(status)) {
                problems.add("section " + section.path("id").asString("?") + " has an unknown status " + status);
            }
            statuses.merge(status, 1, Integer::sum);
        }
        if (!FORMAT.equals(report.path("format").asString(null))) {
            problems.add("unknown report format " + report.path("format").asString(null));
            return new ReportCheck(false, id, title, keyId, sequence, entries, statuses, problems);
        }
        byte[] bytes;
        try {
            bytes = signedBytes(report);
        } catch (IllegalArgumentException e) {
            problems.add("the report cannot be read: " + e.getMessage());
            return new ReportCheck(false, id, title, keyId, sequence, entries, statuses, problems);
        }
        Map<String, SigningKeys.PublicKeyInfo> trusted = PackageVerifier.trustedKeys(pkg, keyList, pinnedPublicKey,
                problems);
        SigningKeys.PublicKeyInfo key = keyId == null ? null : trusted.get(keyId);
        if (key == null) {
            problems.add("the report is signed with key " + keyId + ", which the package does not trust");
        } else if (revoked(pkg, keyList, keyId)) {
            problems.add("the report is signed with revoked key " + keyId);
        } else if (!SigningKeys.verify(key.publicKey(), bytes, report.path("signature").asString(""))) {
            problems.add("the signature of the report is not valid");
        }
        JsonNode checkpoint = pkg.path("checkpoint");
        if (sequence == null || sequence != checkpoint.path("sequence").asLong(-1)
                || !Objects.equals(evidence.path("headHash").asString(null), checkpoint.path("headHash")
                .asString(null))) {
            problems.add("the report is for another checkpoint than the package");
        }
        JsonNode scope = report.path("scope");
        JsonNode packageScope = pkg.path("scope");
        for (String field : List.of("agentId", "principalId", "fromSequence", "toSequence")) {
            if (!Objects.equals(value(scope.path(field)), value(packageScope.path(field)))) {
                problems.add("the report has another " + field + " than the package");
            }
        }
        List<String> hashes = new ArrayList<>();
        for (JsonNode link : pkg.path("links")) {
            if (link.path("entry").isObject()) {
                hashes.add(link.path("hash").asString(""));
            }
        }
        if (entries == null || entries != hashes.size()
                || !entriesDigest(hashes).equals(evidence.path("entriesDigest").asString(""))) {
            problems.add("the report covers other entries than the package discloses");
        }
        return new ReportCheck(problems.isEmpty(), id, title, keyId, sequence, entries, statuses, problems);
    }

    private static boolean revoked(JsonNode pkg, JsonNode keyList, String keyId) {
        List<JsonNode> nodes = new ArrayList<>();
        pkg.path("keys").forEach(nodes::add);
        if (keyList != null) {
            keyList.forEach(nodes::add);
        }
        return nodes.stream().anyMatch(node -> keyId.equals(node.path("keyId").asString(null))
                && node.path("revocation").isObject());
    }

    private static Object value(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isIntegralNumber() ? (Object) node.asLong() : node.asString();
    }

    private static Map<String, Object> object(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("a report must be a JSON object");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (String name : node.propertyNames()) {
            map.put(name, canonical(node.get(name)));
        }
        return map;
    }

    private static Object canonical(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return object(node);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(canonical(item)));
            return list;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.asLong();
        }
        throw new IllegalArgumentException("reports hold only strings, integers, booleans, arrays and objects");
    }
}
