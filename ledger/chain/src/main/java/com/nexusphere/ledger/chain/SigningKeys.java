package com.nexusphere.ledger.chain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class SigningKeys {

    public static final String ALGORITHM = "Ed25519";

    public record PublicKeyInfo(String keyId, PublicKey publicKey) {

        public static PublicKeyInfo of(PublicKey publicKey) {
            return new PublicKeyInfo(keyIdOf(publicKey), publicKey);
        }

        public String encoded() {
            return encode(publicKey);
        }
    }

    private SigningKeys() {
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available", e);
        }
    }

    public static PrivateKey decodePrivate(String base64) {
        try {
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a base64 PKCS#8 Ed25519 private key", e);
        }
    }

    public static PublicKey decodePublic(String base64) {
        try {
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a base64 X.509 Ed25519 public key", e);
        }
    }

    public static String encode(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static String encode(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static String keyIdOf(PublicKey key) {
        return Hashes.sha256(key.getEncoded()).substring(0, 16);
    }

    public static String sign(PrivateKey key, byte[] data) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(key);
            signature.update(data);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    public static boolean verify(PublicKey key, byte[] data, String base64Signature) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(key);
            signature.update(data);
            return signature.verify(Base64.getDecoder().decode(base64Signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean matches(PrivateKey privateKey, PublicKey publicKey) {
        byte[] probe = "nexusphere-ledger/key-check".getBytes(StandardCharsets.UTF_8);
        return verify(publicKey, probe, sign(privateKey, probe));
    }
}
