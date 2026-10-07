package com.nexusphere.ledger.chain;

import java.util.LinkedHashMap;
import java.util.Map;

public record EvidenceLink(long sequence, String previousHash, String contentHash, String hash) {

    public String computeHash() {
        return hashOf(sequence, previousHash, contentHash);
    }

    public static String hashOf(long sequence, String previousHash, String contentHash) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("format", EvidenceEntry.FORMAT);
        link.put("sequence", sequence);
        link.put("previousHash", previousHash);
        link.put("contentHash", contentHash);
        return Hashes.sha256(CanonicalJson.bytes(link));
    }
}
