package com.nexusphere.ledger.chain;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HexFormat;
import java.util.Map;

public record EvidenceStatement(CoseSign1 message, String issuer, String subject, long sequence, String previousHash,
                                String contentHash) {

    public static final String CONTENT_TYPE = "application/vnd.nexusphere.evidence+json";
    public static final String SEQUENCE = "nexusphere-sequence";
    public static final String PREVIOUS_HASH = "nexusphere-previous-hash";

    private static final HexFormat HEX = HexFormat.of();

    public static byte[] sign(EvidenceEntry entry, String issuer, String keyId, PrivateKey key) {
        return sign(entry, issuer, keyId, Signer.of(key));
    }

    public static byte[] sign(EvidenceEntry entry, String issuer, String keyId, Signer key) {
        Map<Object, Object> header = Map.of(
                CoseSign1.ALG, CoseSign1.EDDSA,
                CoseSign1.KID, keyId.getBytes(StandardCharsets.US_ASCII),
                CoseSign1.CWT_CLAIMS, Map.of(CoseSign1.ISS, issuer, CoseSign1.SUB, subjectOf(entry)),
                CoseSign1.PAYLOAD_HASH_ALG, CoseSign1.SHA_256,
                CoseSign1.PREIMAGE_CONTENT_TYPE, CONTENT_TYPE,
                SEQUENCE, entry.sequence(),
                PREVIOUS_HASH, HEX.parseHex(entry.previousHash()));
        return CoseSign1.sign(header, Map.of(), HEX.parseHex(entry.contentHash()), false, key);
    }

    public static String subjectOf(EvidenceEntry entry) {
        return "urn:uuid:" + entry.id();
    }

    public static EvidenceStatement parse(byte[] data) {
        CoseSign1 message = CoseSign1.parse(data);
        Map<Object, Object> header = message.protectedHeader();
        Map<Object, Object> claims = message.claims();
        if (!Long.valueOf(CoseSign1.SHA_256).equals(header.get(CoseSign1.PAYLOAD_HASH_ALG))
                || !CONTENT_TYPE.equals(header.get(CoseSign1.PREIMAGE_CONTENT_TYPE))
                || !(header.get(SEQUENCE) instanceof Long sequence)
                || !(header.get(PREVIOUS_HASH) instanceof byte[] previous) || previous.length != 32
                || !(claims.get(CoseSign1.ISS) instanceof String issuer)
                || !(claims.get(CoseSign1.SUB) instanceof String subject)
                || message.payload() == null || message.payload().length != 32) {
            throw new IllegalArgumentException("Not a Nexusphere evidence statement");
        }
        return new EvidenceStatement(message, issuer, subject, sequence, HEX.formatHex(previous),
                HEX.formatHex(message.payload()));
    }

    public boolean verify(PublicKey key) {
        return message.verify(key, null);
    }

    public String keyId() {
        return message.keyId();
    }

    public String entryHash() {
        return EvidenceLink.hashOf(sequence, previousHash, contentHash);
    }

    public byte[] leafHash() {
        return MerkleTree.leafHash(HEX.parseHex(entryHash()));
    }

    public boolean describes(EvidenceLink link) {
        return link.sequence() == sequence && link.previousHash().equals(previousHash)
                && link.contentHash().equals(contentHash);
    }
}
