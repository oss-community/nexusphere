package com.nexusphere.ledger.chain;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public record CoseSign1(byte[] protectedBytes, Map<Object, Object> protectedHeader, Map<Object, Object> unprotected,
                        byte[] payload, byte[] signature) {

    public static final long TAG = 18;
    public static final long ALG = 1;
    public static final long KID = 4;
    public static final long CWT_CLAIMS = 15;
    public static final long VDS = 395;
    public static final long VDP = 396;
    public static final long PAYLOAD_HASH_ALG = 258;
    public static final long PREIMAGE_CONTENT_TYPE = 259;
    public static final long EDDSA = -8;
    public static final long SHA_256 = -16;
    public static final long ISS = 1;
    public static final long SUB = 2;

    public static byte[] sign(Map<Object, Object> protectedHeader, Map<Object, Object> unprotected, byte[] payload,
                              boolean detached, PrivateKey key) {
        return sign(protectedHeader, unprotected, payload, detached, Signer.of(key));
    }

    public static byte[] sign(Map<Object, Object> protectedHeader, Map<Object, Object> unprotected, byte[] payload,
                              boolean detached, Signer key) {
        byte[] protectedBytes = Cbor.encode(protectedHeader);
        byte[] signature = key.sign(toBeSigned(protectedBytes, payload));
        return Cbor.encode(new Cbor.Tagged(TAG,
                Arrays.asList(protectedBytes, unprotected, detached ? null : payload, signature)));
    }

    public static CoseSign1 parse(byte[] data) {
        Object decoded = Cbor.decode(data);
        if (decoded instanceof Cbor.Tagged(long tag, Object value) && tag == TAG) {
            decoded = value;
        }
        if (!(decoded instanceof List<?> items) || items.size() != 4
                || !(items.get(0) instanceof byte[] protectedBytes)
                || !(items.get(1) instanceof Map<?, ?> unprotected)
                || !(items.get(3) instanceof byte[] signature)
                || !(items.get(2) == null || items.get(2) instanceof byte[])) {
            throw new IllegalArgumentException("Not a COSE_Sign1 message");
        }
        Object header = protectedBytes.length == 0 ? Map.of() : Cbor.decode(protectedBytes);
        if (!(header instanceof Map<?, ?> protectedHeader)) {
            throw new IllegalArgumentException("The COSE protected header is not a map");
        }
        return new CoseSign1(protectedBytes, cast(protectedHeader), cast(unprotected), (byte[]) items.get(2),
                signature);
    }

    public boolean verify(PublicKey key, byte[] detachedPayload) {
        byte[] signed = payload != null ? payload : detachedPayload;
        if (signed == null || !Long.valueOf(EDDSA).equals(protectedHeader.get(ALG))) {
            return false;
        }
        return SigningKeys.verify(key, toBeSigned(protectedBytes, signed),
                Base64.getEncoder().encodeToString(signature));
    }

    public String keyId() {
        return protectedHeader.get(KID) instanceof byte[] kid ? new String(kid, StandardCharsets.US_ASCII)
                : null;
    }

    public Map<Object, Object> claims() {
        return protectedHeader.get(CWT_CLAIMS) instanceof Map<?, ?> claims ? cast(claims) : Map.of();
    }

    private static byte[] toBeSigned(byte[] protectedBytes, byte[] payload) {
        return Cbor.encode(Arrays.asList("Signature1", protectedBytes, new byte[0], payload));
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> cast(Map<?, ?> map) {
        return (Map<Object, Object>) map;
    }
}
