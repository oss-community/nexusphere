package com.nexusphere.ledger.chain;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public record TimestampToken(byte[] encoded, String hashAlgorithm, byte[] messageImprint, Instant genTime,
                             BigInteger serialNumber, BigInteger nonce, String policy,
                             List<X509Certificate> certificates,
                             byte[] signerIdentifier, boolean signerByKeyId, String digestAlgorithm,
                             byte[] signedAttributes, byte[] messageDigestAttribute, byte[] signingCertificateHash,
                             String signingCertificateHashAlgorithm, String signatureAlgorithm, byte[] signature,
                             byte[] content) {

    public static final String SIGNED_DATA = "1.2.840.113549.1.7.2";
    public static final String TST_INFO = "1.2.840.113549.1.9.16.1.4";
    public static final String TIME_STAMPING = "1.3.6.1.5.5.7.3.8";
    private static final String CONTENT_TYPE = "1.2.840.113549.1.9.3";
    private static final String MESSAGE_DIGEST = "1.2.840.113549.1.9.4";
    private static final String SIGNING_CERTIFICATE = "1.2.840.113549.1.9.16.2.12";
    private static final String SIGNING_CERTIFICATE_V2 = "1.2.840.113549.1.9.16.2.47";
    private static final Map<String, String> DIGESTS = Map.of(
            "1.3.14.3.2.26", "SHA-1",
            "2.16.840.1.101.3.4.2.1", "SHA-256",
            "2.16.840.1.101.3.4.2.2", "SHA-384",
            "2.16.840.1.101.3.4.2.3", "SHA-512");
    private static final Map<String, String> SIGNATURES = Map.of(
            "1.2.840.10045.4.3.2", "SHA256withECDSA",
            "1.2.840.10045.4.3.3", "SHA384withECDSA",
            "1.2.840.10045.4.3.4", "SHA512withECDSA",
            "1.2.840.113549.1.1.11", "SHA256withRSA",
            "1.2.840.113549.1.1.12", "SHA384withRSA",
            "1.2.840.113549.1.1.13", "SHA512withRSA",
            "1.3.101.112", "Ed25519");

    public record Verification(boolean valid, Instant genTime, String authority, boolean pinned, String problem) {

        static Verification invalid(String problem) {
            return new Verification(false, null, null, false, problem);
        }
    }

    public static byte[] request(byte[] sha256, BigInteger nonce) {
        byte[] algorithm = Der.encode(Der.SEQUENCE, Der.oid("2.16.840.1.101.3.4.2.1"), Der.encode(Der.NULL));
        byte[] imprint = Der.encode(Der.SEQUENCE, algorithm, Der.encode(Der.OCTET_STRING, sha256));
        return Der.encode(Der.SEQUENCE, Der.integer(BigInteger.ONE), imprint, Der.integer(nonce),
                Der.encode(Der.BOOLEAN, new byte[]{(byte) 0xff}));
    }

    public static TimestampToken fromResponse(byte[] response) {
        List<Der.Node> parts = Der.read(response).expect(Der.SEQUENCE).children();
        int status = parts.getFirst().expect(Der.SEQUENCE).child(0).integer().intValue();
        if (status != 0 && status != 1) {
            throw new IllegalArgumentException("the timestamp authority refused the request with status " + status);
        }
        if (parts.size() < 2) {
            throw new IllegalArgumentException("the timestamp response has no token");
        }
        return parse(parts.get(1).encoded());
    }

    public static TimestampToken parse(String base64) {
        return parse(Base64.getDecoder().decode(base64));
    }

    public static TimestampToken parse(byte[] encoded) {
        Der.Node contentInfo = Der.read(encoded).expect(Der.SEQUENCE);
        if (!SIGNED_DATA.equals(contentInfo.child(0).oid())) {
            throw new IllegalArgumentException("a timestamp token must be CMS signed data");
        }
        Der.Node signedData = contentInfo.child(1).expect(0xa0).child(0).expect(Der.SEQUENCE);
        List<Der.Node> fields = signedData.children();
        Der.Node encapsulated = fields.get(2).expect(Der.SEQUENCE);
        if (!TST_INFO.equals(encapsulated.child(0).oid())) {
            throw new IllegalArgumentException("the signed content is not a TSTInfo");
        }
        byte[] content = encapsulated.child(1).expect(0xa0).child(0).expect(Der.OCTET_STRING).content();
        List<X509Certificate> certificates = new ArrayList<>();
        Der.Node signerInfos = null;
        for (int i = 3; i < fields.size(); i++) {
            Der.Node field = fields.get(i);
            if (field.tag() == 0xa0) {
                certificates.addAll(certificates(field));
            } else if (field.tag() == Der.SET) {
                signerInfos = field;
            }
        }
        if (signerInfos == null || signerInfos.children().size() != 1) {
            throw new IllegalArgumentException("a timestamp token must have exactly one signer");
        }
        List<Der.Node> signer = signerInfos.child(0).expect(Der.SEQUENCE).children();
        Der.Node sid = signer.get(1);
        boolean byKeyId = sid.tag() == 0x80;
        String digestAlgorithm = digest(signer.get(2).child(0).oid());
        Der.Node attributes = signer.get(3).expect(0xa0);
        byte[] signedAttributes = attributes.encoded();
        signedAttributes[0] = (byte) Der.SET;
        byte[] messageDigest = null;
        byte[] certificateHash = null;
        String certificateHashAlgorithm = null;
        boolean tstContentType = false;
        for (Der.Node attribute : attributes.children()) {
            String type = attribute.child(0).oid();
            Der.Node value = attribute.child(1).expect(Der.SET).child(0);
            switch (type) {
                case CONTENT_TYPE -> tstContentType = TST_INFO.equals(value.oid());
                case MESSAGE_DIGEST -> messageDigest = value.expect(Der.OCTET_STRING).content();
                case SIGNING_CERTIFICATE -> {
                    certificateHash = value.child(0).child(0).child(0).expect(Der.OCTET_STRING).content();
                    certificateHashAlgorithm = "SHA-1";
                }
                case SIGNING_CERTIFICATE_V2 -> {
                    Der.Node certId = value.child(0).child(0);
                    Der.Node first = certId.child(0);
                    if (first.tag() == Der.SEQUENCE) {
                        certificateHashAlgorithm = digest(first.child(0).oid());
                        certificateHash = certId.child(1).expect(Der.OCTET_STRING).content();
                    } else {
                        certificateHashAlgorithm = "SHA-256";
                        certificateHash = first.expect(Der.OCTET_STRING).content();
                    }
                }
                default -> {
                }
            }
        }
        if (!tstContentType) {
            throw new IllegalArgumentException("the signed attributes do not name a TSTInfo");
        }
        String signatureAlgorithm = signer.get(4).child(0).oid();
        byte[] signature = signer.get(5).expect(Der.OCTET_STRING).content();
        List<Der.Node> info = Der.read(content).expect(Der.SEQUENCE).children();
        String policy = info.get(1).oid();
        Der.Node imprint = info.get(2).expect(Der.SEQUENCE);
        String hashAlgorithm = digest(imprint.child(0).child(0).oid());
        BigInteger serial = info.get(3).integer();
        Instant genTime = info.get(4).generalizedTime();
        BigInteger nonce = null;
        for (int i = 5; i < info.size(); i++) {
            if (info.get(i).tag() == Der.INTEGER) {
                nonce = info.get(i).integer();
            }
        }
        return new TimestampToken(encoded, hashAlgorithm, imprint.child(1).expect(Der.OCTET_STRING).content(),
                genTime, serial, nonce, policy, List.copyOf(certificates),
                byKeyId ? sid.content() : sid.encoded(), byKeyId, digestAlgorithm, signedAttributes, messageDigest,
                certificateHash, certificateHashAlgorithm, signatureAlgorithm, signature, content);
    }

    public String base64() {
        return Base64.getEncoder().encodeToString(encoded);
    }

    public String imprintHex() {
        return HexFormat.of().formatHex(messageImprint);
    }

    public Verification verify(byte[] expectedSha256, Collection<X509Certificate> trusted) {
        try {
            if (!"SHA-256".equals(hashAlgorithm) || !MessageDigest.isEqual(messageImprint, expectedSha256)) {
                return Verification.invalid("the timestamp is for different data");
            }
            if (messageDigestAttribute == null || !MessageDigest.isEqual(messageDigestAttribute,
                    MessageDigest.getInstance(digestAlgorithm).digest(content))) {
                return Verification.invalid("the timestamp signature does not cover its content");
            }
            X509Certificate signer = signer(trusted);
            if (signer == null) {
                return Verification.invalid("the timestamp does not carry the certificate of its signer");
            }
            if (signingCertificateHash != null && !MessageDigest.isEqual(signingCertificateHash,
                    MessageDigest.getInstance(signingCertificateHashAlgorithm).digest(signer.getEncoded()))) {
                return Verification.invalid("the timestamp names a different signing certificate");
            }
            List<String> usages = signer.getExtendedKeyUsage();
            if (usages == null || !usages.contains(TIME_STAMPING)) {
                return Verification.invalid("the signing certificate is not for timestamping");
            }
            if (!verifySignature(signer.getPublicKey(), signedAttributes, signature)) {
                return Verification.invalid("the timestamp signature is not valid");
            }
            signer.checkValidity(java.util.Date.from(genTime));
            boolean pinned = trusted != null && !trusted.isEmpty();
            if (pinned && !chainsTo(signer, trusted)) {
                return Verification.invalid("the timestamp authority is not trusted");
            }
            return new Verification(true, genTime, signer.getSubjectX500Principal().getName(), pinned, null);
        } catch (GeneralSecurityException | RuntimeException e) {
            return Verification.invalid("the timestamp cannot be verified: " + e.getMessage());
        }
    }

    public static List<X509Certificate> readCertificates(byte[] pemOrDer) {
        try {
            Collection<? extends java.security.cert.Certificate> read = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pemOrDer));
            return read.stream().map(X509Certificate.class::cast).toList();
        } catch (CertificateException e) {
            throw new IllegalArgumentException("the certificates cannot be read", e);
        }
    }

    private X509Certificate signer(Collection<X509Certificate> trusted) throws GeneralSecurityException {
        List<X509Certificate> candidates = new ArrayList<>(certificates);
        if (trusted != null) {
            candidates.addAll(trusted);
        }
        for (X509Certificate certificate : candidates) {
            if (signerByKeyId) {
                byte[] extension = certificate.getExtensionValue("2.5.29.14");
                if (extension != null && Arrays.equals(Der.read(Der.read(extension).content()).content(),
                        signerIdentifier)) {
                    return certificate;
                }
            } else {
                Der.Node sid = Der.read(signerIdentifier);
                byte[] issuer = sid.child(0).encoded();
                BigInteger serial = sid.child(1).integer();
                if (Arrays.equals(certificate.getIssuerX500Principal().getEncoded(), issuer)
                        && certificate.getSerialNumber().equals(serial)) {
                    return certificate;
                }
            }
        }
        return null;
    }

    private boolean chainsTo(X509Certificate certificate, Collection<X509Certificate> trusted) {
        X509Certificate current = certificate;
        for (int depth = 0; depth < 6 && current != null; depth++) {
            for (X509Certificate anchor : trusted) {
                if (anchor.equals(current)) {
                    return true;
                }
                if (issued(anchor, current)) {
                    return true;
                }
            }
            X509Certificate next = null;
            for (X509Certificate candidate : certificates) {
                if (!candidate.equals(current) && issued(candidate, current)) {
                    next = candidate;
                    break;
                }
            }
            current = next;
        }
        return false;
    }

    private boolean issued(X509Certificate issuer, X509Certificate subject) {
        if (!issuer.getSubjectX500Principal().equals(subject.getIssuerX500Principal())) {
            return false;
        }
        try {
            subject.verify(issuer.getPublicKey());
            issuer.checkValidity(java.util.Date.from(genTime));
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private boolean verifySignature(PublicKey key, byte[] data, byte[] signatureBytes) throws GeneralSecurityException {
        String algorithm = SIGNATURES.get(signatureAlgorithm);
        if (algorithm == null) {
            String hash = digestAlgorithm.replace("-", "");
            algorithm = switch (signatureAlgorithm) {
                case "1.2.840.113549.1.1.1" -> hash + "withRSA";
                case "1.2.840.10045.2.1" -> hash + "withECDSA";
                default -> throw new GeneralSecurityException("unsupported signature algorithm " + signatureAlgorithm);
            };
        }
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(key);
        verifier.update(data);
        return verifier.verify(signatureBytes);
    }

    private static List<X509Certificate> certificates(Der.Node set) {
        List<X509Certificate> certificates = new ArrayList<>();
        for (Der.Node child : set.children()) {
            if (child.tag() == Der.SEQUENCE) {
                certificates.addAll(readCertificates(child.encoded()));
            }
        }
        return certificates;
    }

    private static String digest(String oid) {
        String name = DIGESTS.get(oid);
        if (name == null) {
            throw new IllegalArgumentException("unsupported digest algorithm " + oid);
        }
        return name;
    }
}
