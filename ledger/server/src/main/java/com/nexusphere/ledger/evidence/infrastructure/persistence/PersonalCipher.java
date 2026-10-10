package com.nexusphere.ledger.evidence.infrastructure.persistence;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

final class PersonalCipher {

    static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PersonalCipher() {
    }

    record Sealed(byte[] nonce, byte[] ciphertext) {
    }

    static byte[] newKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    static Sealed seal(byte[] key, UUID evidenceId, byte[] plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return new Sealed(nonce, run(Cipher.ENCRYPT_MODE, key, nonce, evidenceId, plaintext));
    }

    static byte[] open(byte[] key, UUID evidenceId, byte[] nonce, byte[] ciphertext) {
        return run(Cipher.DECRYPT_MODE, key, nonce, evidenceId, ciphertext);
    }

    private static byte[] run(int mode, byte[] key, byte[] nonce, UUID evidenceId, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(evidenceId.toString().getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The personal data of evidence " + evidenceId + " cannot be read", e);
        }
    }
}
