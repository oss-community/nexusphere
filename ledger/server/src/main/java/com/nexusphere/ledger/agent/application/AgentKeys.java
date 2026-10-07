package com.nexusphere.ledger.agent.application;

import com.nexusphere.ledger.chain.Hashes;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

final class AgentKeys {

    static final String PREFIX = "nxl_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AgentKeys() {
    }

    static String generate() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    static String hash(String key) {
        return Hashes.sha256(key.getBytes(StandardCharsets.UTF_8));
    }

    static String prefix(String key) {
        return key.substring(0, PREFIX.length() + 6);
    }
}
