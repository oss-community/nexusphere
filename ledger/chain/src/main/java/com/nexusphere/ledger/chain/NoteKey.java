package com.nexusphere.ledger.chain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

public record NoteKey(String name, byte type, PublicKey publicKey) {

    public static final byte ED25519 = 0x01;
    public static final byte COSIGNATURE = 0x04;
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    public NoteKey {
        if (name == null || name.isEmpty() || name.contains("+") || name.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("A note key name must be non-empty without spaces or '+'");
        }
        if (type != ED25519 && type != COSIGNATURE) {
            throw new IllegalArgumentException("Unsupported note key type " + type);
        }
    }

    public static NoteKey parse(String vkey) {
        String[] parts = vkey.strip().split("\\+", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("A verifier key has the form name+hash+key");
        }
        byte[] key = Base64.getDecoder().decode(parts[2]);
        if (key.length != 33) {
            throw new IllegalArgumentException("A verifier key holds a type byte and a 32-byte Ed25519 key");
        }
        NoteKey parsed = new NoteKey(parts[0], key[0], publicKey(Arrays.copyOfRange(key, 1, 33)));
        if (!parsed.hashHex().equals(parts[1])) {
            throw new IllegalArgumentException("The verifier key hash does not match its key");
        }
        return parsed;
    }

    public static byte[] raw(PublicKey key) {
        byte[] encoded = key.getEncoded();
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    public static PublicKey publicKey(byte[] raw) {
        try {
            byte[] encoded = new byte[X509_PREFIX.length + raw.length];
            System.arraycopy(X509_PREFIX, 0, encoded, 0, X509_PREFIX.length);
            System.arraycopy(raw, 0, encoded, X509_PREFIX.length, raw.length);
            return KeyFactory.getInstance(SigningKeys.ALGORITHM).generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not an Ed25519 public key", e);
        }
    }

    public byte[] hash() {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(name.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) '\n');
            sha256.update(type);
            sha256.update(raw(publicKey));
            return Arrays.copyOf(sha256.digest(), 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public String hashHex() {
        return HexFormat.of().formatHex(hash());
    }

    public String vkey() {
        ByteArrayOutputStream key = new ByteArrayOutputStream();
        key.write(type);
        key.writeBytes(raw(publicKey));
        return name + "+" + hashHex() + "+" + Base64.getEncoder().encodeToString(key.toByteArray());
    }
}
