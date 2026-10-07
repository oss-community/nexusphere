package com.nexusphere.ledger.mandate;

import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public record StatusList(String issuer, String uri, Instant issuedAt, Instant expiresAt, int size, BitSet revoked) {

    public static final String TYPE = "statuslist+jwt";
    public static final int DEFAULT_SIZE = 131_072;

    public boolean isRevoked(long index) {
        return index < 0 || index >= size || revoked.get((int) index);
    }

    public String sign(String keyId, PrivateKey key) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", Jws.ALGORITHM);
        header.put("typ", TYPE);
        header.put("kid", keyId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("sub", uri);
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("bits", 1);
        list.put("size", size);
        list.put("lst", Jws.encode(compress(revoked, size)));
        payload.put("status_list", list);
        return Jws.sign(header, payload, key);
    }

    public static StatusList fromPayload(JsonNode payload) {
        JsonNode list = payload.path("status_list");
        if (list.path("bits").asInt(0) != 1) {
            throw new IllegalArgumentException("Only one bit per status is supported");
        }
        int size = list.path("size").asInt(0);
        byte[] bytes = decompress(Base64.getUrlDecoder().decode(list.path("lst").asString("")));
        BitSet bits = new BitSet(size);
        for (int i = 0; i < size && i / 8 < bytes.length; i++) {
            if ((bytes[i / 8] & (1 << (i % 8))) != 0) {
                bits.set(i);
            }
        }
        return new StatusList(payload.path("iss").asString(null), payload.path("sub").asString(null),
                Instant.ofEpochSecond(payload.path("iat").asLong()), Instant.ofEpochSecond(payload.path("exp").asLong()),
                size, bits);
    }

    private static byte[] compress(BitSet bits, int size) {
        byte[] bytes = new byte[(size + 7) / 8];
        bits.stream().filter(i -> i < size).forEach(i -> bytes[i / 8] |= (byte) (1 << (i % 8)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] decompress(byte[] data) {
        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(data))) {
            return inflater.readAllBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("The status list cannot be decompressed");
        }
    }
}
