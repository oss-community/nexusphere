package com.nexusphere.ledger.chain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class Hashes {

    public static final String GENESIS = "0".repeat(64);
    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

    private Hashes() {
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static boolean isSha256(String value) {
        return value != null && SHA_256_HEX.matcher(value).matches();
    }
}
