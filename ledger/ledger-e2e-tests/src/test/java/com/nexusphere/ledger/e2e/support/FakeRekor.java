package com.nexusphere.ledger.e2e.support;

import com.nexusphere.ledger.chain.CanonicalJson;
import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.SigningKeys;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeRekor implements AutoCloseable {

    public static final String ORIGIN = "rekor.test";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final KeyPair keys = SigningKeys.generate();
    private final NoteKey noteKey = new NoteKey(ORIGIN, NoteKey.ED25519, keys.getPublic());
    private final List<byte[]> leaves = new ArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile boolean failing;

    private FakeRekor(HttpServer server) {
        this.server = server;
    }

    public static FakeRekor start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeRekor fake = new FakeRekor(http);
            http.createContext("/api/v2/log/entries", fake::entries);
            http.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public String verifierKey() {
        return noteKey.vkey();
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

    private void entries(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes()).path("hashedRekordRequestV002");
            if (failing) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            byte[] response = respond(request);
            if (response == null) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    private synchronized byte[] respond(JsonNode request) {
        Base64.Decoder base64 = Base64.getDecoder();
        String digest = request.path("digest").asString();
        JsonNode signature = request.path("signature");
        String content = signature.path("content").asString();
        String rawKey = signature.path("verifier").path("publicKey").path("rawBytes").asString();
        if (!"PKIX_ED25519_PH".equals(signature.path("verifier").path("keyDetails").asString())
                || base64.decode(digest).length != 64 || base64.decode(content).length != 64) {
            return null;
        }
        SigningKeys.decodePublic(rawKey);
        byte[] body = CanonicalJson.bytes(Map.of("apiVersion", "0.0.2", "kind", "hashedrekord", "spec",
                Map.of("hashedRekordV002", Map.of(
                        "data", Map.of("algorithm", "SHA2_512", "digest", digest),
                        "signature", Map.of("content", content, "verifier",
                                Map.of("keyDetails", "PKIX_ED25519_PH", "publicKey", Map.of("rawBytes", rawKey)))))));
        leaves.add(MerkleTree.leafHash(body));
        long index = leaves.size() - 1;
        long size = leaves.size();
        MerkleTree.Subtrees tree = MerkleTree.of(leaves);
        byte[] root = MerkleTree.root(tree, size);
        String note = new LogCheckpoint(ORIGIN, size, root).sign(noteKey, keys.getPrivate()).text();
        ObjectNode entry = JSON.createObjectNode();
        entry.put("logIndex", String.valueOf(index));
        entry.putObject("kindVersion").put("kind", "hashedrekord").put("version", "0.0.2");
        entry.put("integratedTime", "0");
        ObjectNode proof = entry.putObject("inclusionProof");
        proof.put("logIndex", String.valueOf(index)).put("rootHash", Base64.getEncoder().encodeToString(root))
                .put("treeSize", String.valueOf(size));
        ArrayNode hashes = proof.putArray("hashes");
        MerkleTree.inclusionProof(tree, index, size).forEach(h -> hashes.add(Base64.getEncoder().encodeToString(h)));
        proof.putObject("checkpoint").put("envelope", note);
        entry.put("canonicalizedBody", Base64.getEncoder().encodeToString(body));
        return JSON.writeValueAsBytes(entry);
    }
}
