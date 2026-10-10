package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.CanonicalJson;
import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.EvidenceStatement;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.KeyRevocation;
import com.nexusphere.ledger.chain.KeyRotation;
import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.LogReceipt;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.chain.Signer;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.chain.Timestamps;
import com.nexusphere.ledger.chain.TrustedKeys;
import com.nexusphere.ledger.mandate.HttpSignatures;
import com.nexusphere.ledger.mandate.KeyBinding;
import com.nexusphere.ledger.mandate.KeyResolver;
import com.nexusphere.ledger.mandate.MandateCheck;
import com.nexusphere.ledger.mandate.MandateClaims;
import com.nexusphere.ledger.mandate.MandateProblem;
import com.nexusphere.ledger.mandate.MandateVerifier;
import com.nexusphere.ledger.mandate.Mandates;
import com.nexusphere.ledger.mandate.SdJwt;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConformanceVectorsTest {

    private static final Path DIR = Path.of("../conformance/vectors");
    private static final boolean WRITE = Boolean.getBoolean("conformance.write");
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final HexFormat HEX = HexFormat.of();
    private static final Base64.Encoder B64 = Base64.getEncoder();

    private static final String LEDGER_SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
    private static final String LEDGER_PUBLIC = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String WITNESS_SEED = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb";
    private static final String AGENT_SEED = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7";
    private static final String AGENT_PUBLIC = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025";
    private static final String WITNESS_PUBLIC = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    private static final String ISSUER = "https://ledger.example";
    private static final String ORIGIN = "ledger.example";
    private static final String WITNESS = "witness.example";
    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00.123456Z");

    private static final PrivateKey LEDGER_KEY = privateKey(LEDGER_SEED);
    private static final PublicKey LEDGER = NoteKey.publicKey(HEX.parseHex(LEDGER_PUBLIC));
    private static final PrivateKey WITNESS_KEY = privateKey(WITNESS_SEED);
    private static final PublicKey WITNESS_PUBLIC_KEY = NoteKey.publicKey(HEX.parseHex(WITNESS_PUBLIC));
    private static final String KEY_ID = SigningKeys.keyIdOf(LEDGER);

    private static PrivateKey privateKey(String seed) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
                    HEX.parseHex("302e020100300506032b657004220420" + seed)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<EvidenceEntry> entries() {
        List<EvidenceEntry> entries = new ArrayList<>();
        String previous = Hashes.GENESIS;
        String[][] actions = {
                {"invoice-agent", "acme", "tools/call", "read_invoice", "ALLOW", null, "SUCCEEDED"},
                {"invoice-agent", "acme", "tools/call", "pay_invoice", "DENY", "NOT_COVERED", "DENIED"},
                {"sales-agent", "globex", "a2a/send", "supplier/sales", "ALLOW", null, "PENDING"}};
        for (int i = 0; i < actions.length; i++) {
            String[] a = actions[i];
            TreeMap<String, String> attributes = new TreeMap<>();
            attributes.put("tool", a[3]);
            attributes.put("note", "café ✓");
            TreeMap<String, String> salts = new TreeMap<>();
            for (String field : List.of("principalId", "target", "reason", "correlationId", "attributes.tool",
                    "attributes.note")) {
                byte[] digest = HEX.parseHex(Hashes.sha256(("salt/" + (i + 1) + "/" + field)
                        .getBytes(StandardCharsets.UTF_8)));
                salts.put(field, Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16)));
            }
            EvidenceEntry entry = new EvidenceEntry(UUID.fromString("00000000-0000-4000-8000-00000000000" + (i + 1)),
                    i + 1, T0.plusSeconds(i), T0.plusSeconds(i).plusMillis(5), a[0], a[1], a[2], a[3], a[4], a[5],
                    null, Hashes.sha256(a[3].getBytes()), null, a[6], "conversation-1", attributes, salts, null,
                    previous, null).sealed();
            entries.add(entry);
            previous = entry.hash();
        }
        return entries;
    }

    private static Map<String, Object> values(EvidenceEntry entry) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("principalId", entry.principalId());
        values.put("target", entry.target());
        values.put("reason", entry.reason());
        values.put("correlationId", entry.correlationId());
        values.put("attributes", entry.attributes());
        return values;
    }

    private static List<byte[]> leaves(List<EvidenceEntry> entries) {
        return entries.stream().map(e -> MerkleTree.leafHash(HEX.parseHex(e.hash()))).toList();
    }

    private static JsonNode check(String name, Object vector) throws IOException {
        JsonNode node = JSON.readTree(JSON.writeValueAsString(vector));
        Path file = DIR.resolve(name);
        if (WRITE) {
            Files.createDirectories(DIR);
            Files.writeString(file, JSON.writeValueAsString(node) + "\n");
        }
        JsonNode stored = JSON.readTree(file.toFile());
        assertThat(stored).as(name).isEqualTo(node);
        return stored;
    }

    @Test
    void keys() throws IOException {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("description", "RFC 8032 test keys 1 (ledger) and 2 (witness); never use them for real evidence");
        v.put("ledgerSeed", LEDGER_SEED);
        v.put("ledgerPrivateKey", SigningKeys.encode(LEDGER_KEY));
        v.put("ledgerPublicKey", SigningKeys.encode(LEDGER));
        v.put("ledgerKeyId", KEY_ID);
        v.put("logVerifierKey", new NoteKey(ORIGIN, NoteKey.ED25519, LEDGER).vkey());
        v.put("witnessSeed", WITNESS_SEED);
        v.put("witnessPublicKey", SigningKeys.encode(WITNESS_PUBLIC_KEY));
        v.put("witnessVerifierKey", new NoteKey(WITNESS, NoteKey.COSIGNATURE, WITNESS_PUBLIC_KEY).vkey());
        check("keys.json", v);

        assertThat(SigningKeys.matches(LEDGER_KEY, LEDGER)).isTrue();
        assertThat(SigningKeys.matches(WITNESS_KEY, WITNESS_PUBLIC_KEY)).isTrue();
    }

    @Test
    void canonicalJson() throws IOException {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", List.of(3, "two", true));
        nested.put("a", null);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("b", "text with \"quotes\" and \\ and é and ✓");
        input.put("a", 42);
        input.put("c", nested);
        input.put("aa", -7);
        List<Map<String, Object>> cases = List.of(
                Map.of("input", input, "canonical", CanonicalJson.write(input),
                        "sha256", Hashes.sha256(CanonicalJson.bytes(input))));
        check("canonical-json.json", cases);
    }

    @Test
    void evidenceChain() throws IOException {
        List<Map<String, Object>> v = new ArrayList<>();
        for (EvidenceEntry entry : entries()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("values", values(entry));
            item.put("salts", entry.salts());
            item.put("content", entry.canonicalContent());
            item.put("canonicalContent", CanonicalJson.write(entry.canonicalContent()));
            item.put("sequence", entry.sequence());
            item.put("previousHash", entry.previousHash());
            item.put("contentHash", entry.contentHash());
            item.put("hash", entry.hash());
            v.add(item);
        }
        Checkpoint checkpoint = new Checkpoint(3, entries().getLast().hash(), T0.plusSeconds(60), KEY_ID);
        SignedCheckpoint signed = new SignedCheckpoint(checkpoint,
                SigningKeys.sign(LEDGER_KEY, checkpoint.signedBytes()));
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("entries", v);
        vector.put("checkpoint", Map.of("signedBytes", new String(checkpoint.signedBytes()),
                "signature", signed.signature()));
        check("evidence-chain.json", vector);

        assertThat(signed.verify(SigningKeys.PublicKeyInfo.of(LEDGER))).isTrue();
    }

    @Test
    void merkleTree() throws IOException {
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            leaves.add(MerkleTree.leafHash(new byte[]{(byte) i}));
        }
        MerkleTree.Subtrees tree = MerkleTree.of(leaves);
        List<Object> roots = new ArrayList<>();
        List<Object> inclusion = new ArrayList<>();
        List<Object> consistency = new ArrayList<>();
        for (int size = 0; size <= 8; size++) {
            roots.add(Map.of("size", size, "root", HEX.formatHex(MerkleTree.root(tree, size))));
            for (int index = 0; index < size; index++) {
                inclusion.add(Map.of("index", index, "size", size,
                        "proof", hex(MerkleTree.inclusionProof(tree, index, size))));
            }
            for (int first = 1; first <= size; first++) {
                consistency.add(Map.of("first", first, "second", size,
                        "proof", hex(MerkleTree.consistencyProof(tree, first, size))));
            }
        }
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("description", "RFC 9162 tree over leaves whose data is the single byte 0x00, 0x01, ... 0x07");
        vector.put("leafHashes", hex(leaves));
        vector.put("roots", roots);
        vector.put("inclusionProofs", inclusion);
        vector.put("consistencyProofs", consistency);
        JsonNode stored = check("merkle-tree.json", vector);

        for (JsonNode proof : stored.path("inclusionProofs")) {
            int size = proof.path("size").asInt();
            assertThat(MerkleTree.verifyInclusion(leaves.get(proof.path("index").asInt()), proof.path("index").asLong(),
                    size, unhex(proof.path("proof")), MerkleTree.root(tree, size))).isTrue();
        }
    }

    @Test
    void signedNoteAndCosignature() throws IOException {
        List<EvidenceEntry> entries = entries();
        byte[] root = MerkleTree.root(MerkleTree.of(leaves(entries)), entries.size());
        LogCheckpoint.Note note = new LogCheckpoint(ORIGIN, entries.size(), root)
                .sign(new NoteKey(ORIGIN, NoteKey.ED25519, LEDGER), LEDGER_KEY);
        NoteKey witness = new NoteKey(WITNESS, NoteKey.COSIGNATURE, WITNESS_PUBLIC_KEY);
        LogCheckpoint.Note cosigned = note.with(LogCheckpoint.cosign(note.body(), witness, WITNESS_KEY, 1_760_000_000L));
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("description", "Log checkpoint over the three evidence-chain entries, cosigned at time 1760000000");
        vector.put("rootHash", B64.encodeToString(root));
        vector.put("note", note.text());
        vector.put("cosignedNote", cosigned.text());
        JsonNode stored = check("signed-note.json", vector);

        LogCheckpoint.Note parsed = LogCheckpoint.parse(stored.path("cosignedNote").asString());
        assertThat(parsed.signedBy(new NoteKey(ORIGIN, NoteKey.ED25519, LEDGER))).isTrue();
        assertThat(parsed.cosignedBy(witness)).contains(1_760_000_000L);
    }

    @Test
    void keyHistory() throws IOException {
        SigningKeys.PublicKeyInfo first = SigningKeys.PublicKeyInfo.of(LEDGER);
        SigningKeys.PublicKeyInfo second = SigningKeys.PublicKeyInfo.of(WITNESS_PUBLIC_KEY);
        Instant activatedAt = Instant.parse("2026-10-02T08:00:00Z");
        Instant compromisedAt = Instant.parse("2026-10-03T12:00:00.5Z");
        Instant revokedAt = Instant.parse("2026-10-04T09:30:00Z");
        KeyRotation rotation = KeyRotation.issue(second, WITNESS_KEY, first.keyId(), LEDGER_KEY, activatedAt);
        KeyRevocation revocation = KeyRevocation.issue(first.keyId(), compromisedAt, revokedAt, "backup copy leaked",
                second.keyId(), Signer.of(WITNESS_KEY));
        Map<String, Object> rotationContent = new LinkedHashMap<>();
        rotationContent.put("format", KeyRotation.FORMAT);
        rotationContent.put("keyId", second.keyId());
        rotationContent.put("algorithm", SigningKeys.ALGORITHM);
        rotationContent.put("publicKey", second.encoded());
        rotationContent.put("previousKeyId", first.keyId());
        rotationContent.put("activatedAt", "2026-10-02T08:00:00.000000Z");
        Map<String, Object> revocationContent = new LinkedHashMap<>();
        revocationContent.put("format", KeyRevocation.FORMAT);
        revocationContent.put("keyId", first.keyId());
        revocationContent.put("compromisedAt", "2026-10-03T12:00:00.500000Z");
        revocationContent.put("revokedAt", "2026-10-04T09:30:00.000000Z");
        revocationContent.put("reason", "backup copy leaked");
        revocationContent.put("revokerKeyId", second.keyId());
        Map<String, Object> rotationVector = new LinkedHashMap<>();
        rotationVector.put("keyId", second.keyId());
        rotationVector.put("publicKey", second.encoded());
        rotationVector.put("previousKeyId", first.keyId());
        rotationVector.put("activatedAt", "2026-10-02T08:00:00.000000Z");
        rotationVector.put("signedContent", CanonicalJson.write(rotationContent));
        rotationVector.put("keySignature", rotation.keySignature());
        rotationVector.put("previousKeySignature", rotation.previousKeySignature());
        Map<String, Object> revocationVector = new LinkedHashMap<>();
        revocationVector.put("keyId", first.keyId());
        revocationVector.put("compromisedAt", "2026-10-03T12:00:00.500000Z");
        revocationVector.put("revokedAt", "2026-10-04T09:30:00.000000Z");
        revocationVector.put("reason", "backup copy leaked");
        revocationVector.put("revokerKeyId", second.keyId());
        revocationVector.put("signedContent", CanonicalJson.write(revocationContent));
        revocationVector.put("signature", revocation.signature());
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("description", "Test key 2 replaces test key 1 with an endorsed rotation, then revokes it as "
                + "compromised; a cosignature before compromisedAt proves a checkpoint of key 1");
        vector.put("rotation", rotationVector);
        vector.put("revocation", revocationVector);
        vector.put("compromisedEpochSecond", compromisedAt.getEpochSecond());
        JsonNode stored = check("key-history.json", vector);

        assertThat(CanonicalJson.write(rotationContent)).isEqualTo(stored.path("rotation").path("signedContent")
                .asString());
        assertThat(rotation.verifiedByPrevious(first)).isTrue();
        assertThat(revocation.verify(second)).isTrue();
        TrustedKeys trusted = TrustedKeys.from(second, List.of(first, second), List.of(rotation),
                List.of(revocation));
        assertThat(trusted.revocation(first.keyId())).contains(revocation);
    }

    @Test
    void scittStatementsAndReceipts() throws IOException {
        List<EvidenceEntry> entries = entries();
        MerkleTree.Subtrees tree = MerkleTree.of(leaves(entries));
        byte[] root = MerkleTree.root(tree, entries.size());
        List<Object> v = new ArrayList<>();
        for (EvidenceEntry entry : entries) {
            long index = entry.sequence() - 1;
            v.add(Map.of("sequence", entry.sequence(),
                    "statement", HEX.formatHex(EvidenceStatement.sign(entry, ISSUER, KEY_ID, LEDGER_KEY)),
                    "receipt", HEX.formatHex(LogReceipt.sign(ORIGIN, KEY_ID, entries.size(), index,
                            MerkleTree.inclusionProof(tree, index, entries.size()), root, LEDGER_KEY))));
        }
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("description", "COSE_Sign1 statements and RFC 9942 receipts, hex, for the evidence-chain entries");
        vector.put("rootHash", HEX.formatHex(root));
        vector.put("items", v);
        JsonNode stored = check("scitt.json", vector);

        for (JsonNode item : stored.path("items")) {
            EvidenceStatement statement = EvidenceStatement.parse(HEX.parseHex(item.path("statement").asString()));
            LogReceipt receipt = LogReceipt.parse(HEX.parseHex(item.path("receipt").asString()));
            assertThat(statement.verify(LEDGER)).isTrue();
            assertThat(statement.describes(entries.get((int) statement.sequence() - 1).link())).isTrue();
            assertThat(receipt.verify(statement.leafHash(), LEDGER)).isTrue();
        }
    }

    @Test
    void sdJwtMandate() throws IOException {
        Path file = DIR.resolve("mandate.json");
        MandateClaims claims = new MandateClaims(ISSUER, UUID.fromString("00000000-0000-4000-8000-0000000000aa"),
                "sales-agent", "globex", "https://supplier.example", List.of("a2a/send"), List.of("supplier/*"), 5L,
                UUID.fromString("00000000-0000-4000-8000-0000000000bb"), Hashes.sha256("terms".getBytes()),
                T0.truncatedTo(ChronoUnit.SECONDS), T0.truncatedTo(ChronoUnit.SECONDS),
                Instant.parse("2027-10-01T08:00:00Z"), ISSUER + "/public/v1/mandates/status", 3);
        if (WRITE) {
            String token = Mandates.issue(claims, KEY_ID, LEDGER_KEY);
            Map<String, Object> vector = new LinkedHashMap<>();
            vector.put("description", "SD-JWT VC mandate; salts are random, so check it rather than reproduce it");
            vector.put("token", token);
            vector.put("presentedWithGrantOnly", SdJwt.parse(token).present(Set.of("grant")));
            vector.put("claims", claims.toPayload());
            Files.writeString(file, JSON.writeValueAsString(vector) + "\n");
        }
        JsonNode stored = JSON.readTree(file.toFile());
        MandateVerifier verifier = MandateVerifier.builder().trustIssuer(ISSUER).keys(KeyResolver.fixed(ISSUER, LEDGER))
                .skipStatus().clock(Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC)).build();

        MandateCheck full = verifier.verify(stored.path("token").asString(), "a2a/send", "supplier/orders");
        MandateCheck presented = verifier.verify(stored.path("presentedWithGrantOnly").asString());

        assertThat(full.problems()).isEmpty();
        assertThat(full.claims()).isEqualTo(claims);
        assertThat(JSON.readTree(JSON.writeValueAsString(full.claims().toPayload()))).isEqualTo(stored.path("claims"));
        assertThat(presented.problems()).isEmpty();
        assertThat(presented.claims().principalId()).isNull();
    }

    @Test
    void keyBoundMandate() throws IOException {
        Path file = DIR.resolve("mandate-key-binding.json");
        PrivateKey agent = privateKey(AGENT_SEED);
        PublicKey agentPublic = NoteKey.publicKey(HEX.parseHex(AGENT_PUBLIC));
        Instant presentedAt = T0.truncatedTo(ChronoUnit.SECONDS).plusSeconds(3600);
        String nonce = Hashes.sha256("{\"jsonrpc\":\"2.0\"}".getBytes());
        MandateClaims claims = new MandateClaims(ISSUER, UUID.fromString("00000000-0000-4000-8000-0000000000cc"),
                "sales-agent", "globex", "https://supplier.example", List.of("a2a/send"), List.of("supplier/*"), null,
                UUID.fromString("00000000-0000-4000-8000-0000000000bb"), Hashes.sha256("terms".getBytes()),
                T0.truncatedTo(ChronoUnit.SECONDS), T0.truncatedTo(ChronoUnit.SECONDS),
                Instant.parse("2027-10-01T08:00:00Z"), ISSUER + "/public/v1/mandates/status", 4, agentPublic);
        if (WRITE) {
            String token = Mandates.issue(claims, KEY_ID, LEDGER_KEY);
            Map<String, Object> vector = new LinkedHashMap<>();
            vector.put("description", "SD-JWT VC mandate bound to the agent key with cnf, presented with a KB-JWT");
            vector.put("agentSeed", AGENT_SEED);
            vector.put("agentPublicKey", SigningKeys.encode(agentPublic));
            vector.put("audience", "https://supplier.example");
            vector.put("nonce", nonce);
            vector.put("presentedAt", presentedAt.getEpochSecond());
            vector.put("token", token);
            vector.put("presentation", KeyBinding.present(token, agent, "https://supplier.example", nonce,
                    presentedAt));
            vector.put("sdHash", KeyBinding.sdHash(token));
            vector.put("claims", claims.toPayload());
            Files.writeString(file, JSON.writeValueAsString(vector) + "\n");
        }
        JsonNode stored = JSON.readTree(file.toFile());
        MandateVerifier verifier = MandateVerifier.builder().trustIssuer(ISSUER).keys(KeyResolver.fixed(ISSUER, LEDGER))
                .audience("https://supplier.example").skipStatus()
                .clock(Clock.fixed(presentedAt.plusSeconds(10), ZoneOffset.UTC)).build();

        MandateCheck bound = verifier.verifyBound(stored.path("presentation").asString(), nonce, "a2a/send",
                "supplier/orders");
        MandateCheck bare = verifier.verifyBound(stored.path("token").asString(), nonce);
        MandateCheck replayed = verifier.verifyBound(stored.path("presentation").asString(), "00".repeat(32));

        assertThat(bound.problems()).isEmpty();
        assertThat(bound.claims()).isEqualTo(claims);
        assertThat(JSON.readTree(JSON.writeValueAsString(bound.claims().toPayload()))).isEqualTo(stored.path("claims"));
        assertThat(KeyBinding.sdHash(stored.path("token").asString())).isEqualTo(stored.path("sdHash").asString());
        assertThat(bare.has(MandateProblem.KEY_BINDING_MISSING)).isTrue();
        assertThat(replayed.has(MandateProblem.KEY_BINDING_INVALID)).isTrue();
    }

    @Test
    void httpMessageSignature() throws IOException {
        URI url = URI.create("https://ledger.supplier.example/a2a/in/sales");
        byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"message/send\"}"
                .getBytes(StandardCharsets.UTF_8);
        Instant created = T0.truncatedTo(ChronoUnit.SECONDS);
        Map<String, String> headers = HttpSignatures.sign("POST", url, body, KEY_ID, Signer.of(LEDGER_KEY), created,
                "c2lnbmF0dXJlLW5vbmNl");
        Map<String, String> lower = new LinkedHashMap<>();
        headers.forEach((name, value) -> lower.put(name.toLowerCase(Locale.ROOT), value));
        String input = headers.get(HttpSignatures.SIGNATURE_INPUT);
        String base = HttpSignatures.signatureBase("POST", url,
                List.of("@method", "@authority", "@path", "content-digest"), lower::get,
                input.substring(input.indexOf('=') + 1));
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("description", "RFC 9421 HTTP message signature of an outbound request, signed with test key 1");
        vector.put("method", "POST");
        vector.put("url", url.toString());
        vector.put("body", new String(body, StandardCharsets.UTF_8));
        vector.put("keyId", KEY_ID);
        vector.put("created", created.getEpochSecond());
        vector.put("nonce", "c2lnbmF0dXJlLW5vbmNl");
        vector.put("headers", headers);
        vector.put("signatureBase", base);
        JsonNode stored = check("http-signature.json", vector);
        HttpSignatures.Verified verified = HttpSignatures.verify("POST", url,
                name -> stored.path("headers").path(header(stored, name)).asString(null), body,
                keyId -> KEY_ID.equals(keyId) ? Optional.of(LEDGER) : Optional.empty(), created.plusSeconds(10),
                Duration.ofSeconds(60));

        assertThat(verified.keyId()).isEqualTo(KEY_ID);
    }

    @Test
    void complianceCheckpoint() throws IOException {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", "example");
        profile.put("version", 1);
        profile.put("name", "Example jurisdiction");
        profile.put("description", "A profile used only by the conformance vectors.");
        profile.put("retention", List.of(ordered("actions", "*", "minimum", "P6M"),
                ordered("actions", "mcp/*", "minimum", "P5Y")));
        profile.put("erasure", ordered("onRequest", true, "deadline", "P1M"));
        profile.put("residency", List.of("eea"));
        profile.put("requiredFields", List.of("attributes.model", "target"));
        profile.put("timestampAuthorities", List.of("https://tsa.example"));
        profile.put("reports", List.of("dispute"));
        String digest = Hashes.sha256(CanonicalJson.bytes(profile));
        String baseline = Hashes.sha256("baseline".getBytes(StandardCharsets.UTF_8));
        Checkpoint checkpoint = new Checkpoint(3, entries().getLast().hash(), T0.plusSeconds(60), KEY_ID,
                List.of(new Checkpoint.Profile("example", digest), new Checkpoint.Profile("baseline", baseline)));
        SignedCheckpoint signed = new SignedCheckpoint(checkpoint,
                SigningKeys.sign(LEDGER_KEY, checkpoint.signedBytes()));
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("description", "A checkpoint that signs the active compliance profiles, signed with test key 1");
        vector.put("profile", profile);
        vector.put("canonicalProfile", CanonicalJson.write(profile));
        vector.put("profileDigest", digest);
        vector.put("checkpoint", ordered("format", checkpoint.format(), "sequence", 3,
                "headHash", checkpoint.headHash(), "createdAt", Timestamps.format(checkpoint.createdAt()),
                "keyId", KEY_ID, "profiles", checkpoint.profiles().stream().map(Checkpoint.Profile::toMap).toList(),
                "signedBytes", new String(checkpoint.signedBytes(), StandardCharsets.UTF_8),
                "signature", signed.signature()));
        check("compliance-checkpoint.json", vector);

        assertThat(signed.verify(SigningKeys.PublicKeyInfo.of(LEDGER))).isTrue();
        assertThat(checkpoint.profiles()).extracting(Checkpoint.Profile::id).containsExactly("baseline", "example");
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static String header(JsonNode stored, String name) {
        for (String field : stored.path("headers").propertyNames()) {
            if (field.equalsIgnoreCase(name)) {
                return field;
            }
        }
        return name;
    }

    private static List<String> hex(List<byte[]> hashes) {
        return hashes.stream().map(HEX::formatHex).toList();
    }

    private static List<byte[]> unhex(JsonNode array) {
        List<byte[]> hashes = new ArrayList<>();
        array.forEach(node -> hashes.add(HEX.parseHex(node.asString())));
        return hashes;
    }
}
