package com.nexusphere.ledger.chain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GrantTerms(
        UUID id,
        String principalId,
        String agentId,
        List<String> actions,
        List<String> targets,
        Instant notBefore,
        Instant expiresAt,
        Long maxUses,
        Instant createdAt) {

    public static final String FORMAT = "nexusphere-ledger/grant/v1";

    public GrantTerms {
        actions = List.copyOf(actions);
        targets = List.copyOf(targets);
        notBefore = notBefore == null ? null : Timestamps.normalize(notBefore);
        expiresAt = Timestamps.normalize(expiresAt);
        createdAt = Timestamps.normalize(createdAt);
    }

    public Map<String, Object> canonicalContent() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", FORMAT);
        content.put("id", id.toString());
        content.put("principalId", principalId);
        content.put("agentId", agentId);
        content.put("actions", actions);
        content.put("targets", targets);
        content.put("notBefore", notBefore == null ? null : Timestamps.format(notBefore));
        content.put("expiresAt", Timestamps.format(expiresAt));
        content.put("maxUses", maxUses);
        content.put("createdAt", Timestamps.format(createdAt));
        return content;
    }

    public String hash() {
        return Hashes.sha256(CanonicalJson.bytes(canonicalContent()));
    }

    public boolean covers(String action, String target) {
        return actions.stream().anyMatch(pattern -> matches(pattern, action))
                && targets.stream().anyMatch(pattern -> matches(pattern, target));
    }

    public static boolean matches(String pattern, String value) {
        if (value == null) {
            return "*".equals(pattern);
        }
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(value);
    }
}
