package com.nexusphere.ledger.e2e.support;

import com.nexusphere.ledger.chain.Der;
import com.nexusphere.ledger.chain.TimestampToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class FakeTimestampAuthority implements AutoCloseable {

    private static final String SHA256 = "2.16.840.1.101.3.4.2.1";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final HttpServer server;
    private final PrivateKey key;
    private final X509Certificate certificate;
    private final AtomicLong serial = new AtomicLong(1);
    private final AtomicInteger requests = new AtomicInteger();
    private volatile boolean failing;

    private FakeTimestampAuthority(HttpServer server, PrivateKey key, X509Certificate certificate) {
        this.server = server;
        this.key = key;
        this.certificate = certificate;
    }

    public static FakeTimestampAuthority start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeTimestampAuthority fake = new FakeTimestampAuthority(http, privateKey(resource("tsa/tsa.key")),
                    TimestampToken.readCertificates(resource("tsa/tsa.pem").getBytes(StandardCharsets.US_ASCII))
                            .getFirst());
            http.createContext("/tsr", fake::timestamp);
            http.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/tsr";
    }

    public static Path certificates(Path directory) {
        try {
            Path file = directory.resolve("tsa-chain.pem");
            Files.writeString(file, resource("tsa/tsa.pem") + resource("tsa/root.pem"));
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int requests() {
        return requests.get();
    }

    public void failing(boolean failing) {
        this.failing = failing;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void timestamp(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (failing) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            byte[] response = respond(request);
            exchange.getResponseHeaders().set("Content-Type", "application/timestamp-reply");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private byte[] respond(byte[] request) throws GeneralSecurityException {
        List<Der.Node> fields = Der.read(request).children();
        byte[] imprint = fields.get(1).encoded();
        BigInteger nonce = null;
        for (Der.Node field : fields.subList(2, fields.size())) {
            if (field.tag() == Der.INTEGER) {
                nonce = field.integer();
            }
        }
        byte[] info = Der.encode(Der.SEQUENCE, Der.integer(BigInteger.ONE), Der.oid("1.2.3.4.1"), imprint,
                Der.integer(BigInteger.valueOf(serial.getAndIncrement())),
                Der.encode(Der.GENERALIZED_TIME, TIME.format(Instant.now()).getBytes(StandardCharsets.US_ASCII)),
                nonce == null ? new byte[0] : Der.integer(nonce));
        byte[] sha256 = algorithm(SHA256);
        byte[] certificateHash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        byte[] attributes = Der.encode(Der.SET,
                attribute("1.2.840.113549.1.9.3", Der.oid(TimestampToken.TST_INFO)),
                attribute("1.2.840.113549.1.9.4",
                        Der.encode(Der.OCTET_STRING, MessageDigest.getInstance("SHA-256").digest(info))),
                attribute("1.2.840.113549.1.9.16.2.47", Der.encode(Der.SEQUENCE, Der.encode(Der.SEQUENCE,
                        Der.encode(Der.SEQUENCE, Der.encode(Der.OCTET_STRING, certificateHash))))));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(attributes);
        byte[] signedAttributes = attributes.clone();
        signedAttributes[0] = (byte) 0xa0;
        byte[] issuerAndSerial = Der.encode(Der.SEQUENCE, certificate.getIssuerX500Principal().getEncoded(),
                Der.integer(certificate.getSerialNumber()));
        byte[] signerInfo = Der.encode(Der.SEQUENCE, Der.integer(BigInteger.ONE), issuerAndSerial, sha256,
                signedAttributes, Der.encode(Der.SEQUENCE, Der.oid("1.2.840.10045.4.3.2")),
                Der.encode(Der.OCTET_STRING, signer.sign()));
        byte[] signedData = Der.encode(Der.SEQUENCE, Der.integer(BigInteger.valueOf(3)), Der.encode(Der.SET, sha256),
                Der.encode(Der.SEQUENCE, Der.oid(TimestampToken.TST_INFO),
                        Der.encode(0xa0, Der.encode(Der.OCTET_STRING, info))),
                Der.encode(0xa0, certificate.getEncoded()), Der.encode(Der.SET, signerInfo));
        byte[] token = Der.encode(Der.SEQUENCE, Der.oid(TimestampToken.SIGNED_DATA), Der.encode(0xa0, signedData));
        return Der.encode(Der.SEQUENCE, Der.encode(Der.SEQUENCE, Der.integer(BigInteger.ZERO)), token);
    }

    private static byte[] algorithm(String oid) {
        return Der.encode(Der.SEQUENCE, Der.oid(oid), Der.encode(Der.NULL));
    }

    private static byte[] attribute(String oid, byte[] value) {
        return Der.encode(Der.SEQUENCE, Der.oid(oid), Der.encode(Der.SET, value));
    }

    private static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static String resource(String name) {
        try (InputStream in = FakeTimestampAuthority.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
