package com.nexusphere.ledger.chain;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record Checkpoint(long sequence, String headHash, Instant createdAt, String keyId, List<Profile> profiles) {

    public static final String FORMAT = "nexusphere-ledger/checkpoint/v1";
    public static final String FORMAT_WITH_PROFILES = "nexusphere-ledger/checkpoint/v2";

    public Checkpoint {
        Objects.requireNonNull(headHash, "headHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(keyId, "keyId");
        createdAt = Timestamps.normalize(createdAt);
        profiles = profiles == null ? null
                : profiles.stream().sorted(Comparator.comparing(Profile::id)).toList();
    }

    public Checkpoint(long sequence, String headHash, Instant createdAt, String keyId) {
        this(sequence, headHash, createdAt, keyId, null);
    }

    public String format() {
        return profiles == null ? FORMAT : FORMAT_WITH_PROFILES;
    }

    public byte[] signedBytes() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", format());
        content.put("sequence", sequence);
        content.put("headHash", headHash);
        content.put("createdAt", Timestamps.format(createdAt));
        content.put("keyId", keyId);
        if (profiles != null) {
            content.put("profiles", profiles.stream().map(Profile::toMap).toList());
        }
        return CanonicalJson.bytes(content);
    }

    public record Profile(String id, String digest) {

        private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

        public Profile {
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("A compliance profile id must match " + ID.pattern());
            }
            if (!Hashes.isSha256(digest)) {
                throw new IllegalArgumentException("A compliance profile digest must be a lowercase hex SHA-256");
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("digest", digest);
            return map;
        }
    }
}
