package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.LogTiles;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.SigningKeys;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.SimpleFileServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TileCliTest {

    private static final String ORIGIN = "ledger.example.test";

    private final KeyPair keys = SigningKeys.generate();
    private final Map<String, byte[]> served = new HashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;

    @TempDir
    Path dir;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static List<byte[]> entries(int count) throws Exception {
        List<byte[]> entries = new ArrayList<>();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < count; i++) {
            entries.add(sha.digest(("entry " + i).getBytes(StandardCharsets.UTF_8)));
        }
        return entries;
    }

    private void publish(List<byte[]> entries) {
        List<byte[]> leaves = entries.stream().map(MerkleTree::leafHash).toList();
        MerkleTree.Subtrees subtrees = MerkleTree.of(leaves);
        served.clear();
        for (LogTiles.Tile tile : LogTiles.tiles(entries.size())) {
            long from = tile.index() * LogTiles.WIDTH;
            if (tile.entries()) {
                served.put(tile.path(), LogTiles.bundle(entries.subList((int) from, (int) from + tile.width())));
            } else {
                List<byte[]> hashes = new ArrayList<>();
                for (int j = 0; j < tile.width(); j++) {
                    hashes.add(subtrees.perfect(tile.level() * LogTiles.HEIGHT, from + j));
                }
                served.put(tile.path(), LogTiles.tile(hashes));
            }
        }
        LogCheckpoint checkpoint = new LogCheckpoint(ORIGIN, entries.size(), MerkleTree.root(subtrees, entries.size()));
        served.put("checkpoint", checkpoint.sign(new NoteKey(ORIGIN, NoteKey.ED25519, keys.getPublic()),
                keys.getPrivate()).text().getBytes(StandardCharsets.UTF_8));
    }

    private String serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/log/", exchange -> {
            requests.incrementAndGet();
            byte[] body = served.get(exchange.getRequestURI().getPath().substring("/log/".length()));
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/log";
    }

    private int run(String... args) {
        return VerifierCli.run(args, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
    }

    private String key() {
        return SigningKeys.encode(keys.getPublic());
    }

    @Test
    void anArchivedLogVerifiesAndGrowsIncrementally() throws Exception {
        List<byte[]> all = entries(700);
        publish(all.subList(0, 300));
        String url = serve();
        assertThat(run("archive", url, dir.toString())).isEqualTo(VerifierCli.VALID);
        assertThat(run("tiles", "--public-key", key(), dir.toString())).isEqualTo(VerifierCli.VALID);
        assertThat(dir.resolve("tile/0/001.p/44")).exists();

        publish(all);
        requests.set(0);
        TileCli.ArchiveReport report = TileCli.archive(url + "/", dir, HttpClient.newHttpClient());
        assertThat(report.treeSize()).isEqualTo(700);
        assertThat(report.kept()).isEqualTo(2);
        assertThat(report.removed()).isEqualTo(3);
        assertThat(requests.get()).isEqualTo(1 + report.fetched());
        assertThat(dir.resolve("tile/0/001.p/44")).doesNotExist();
        TileCli.TileReport verified = TileCli.verify(dir, keys.getPublic(), null);
        assertThat(verified.problems()).isEmpty();
        assertThat(verified.entries()).isEqualTo(700);
        assertThat(verified.tiles()).isEqualTo(3 + 1 + 3);
    }

    @Test
    void aTamperedEntryIsCaught() throws Exception {
        publish(entries(20));
        TileCli.archive(serve() + "/", dir, HttpClient.newHttpClient());
        byte[] bundle = Files.readAllBytes(dir.resolve("tile/entries/000.p/20"));
        bundle[5] ^= 1;
        Files.write(dir.resolve("tile/entries/000.p/20"), bundle);
        assertThat(TileCli.verify(dir, keys.getPublic(), null).problems())
                .containsExactly("Entry 1 does not hash to its leaf in tile/0");
    }

    @Test
    void aRewrittenTreeDoesNotMatchTheSignedCheckpoint() throws Exception {
        publish(entries(20));
        TileCli.archive(serve() + "/", dir, HttpClient.newHttpClient());
        List<byte[]> other = entries(21).subList(1, 21);
        Files.write(dir.resolve("tile/entries/000.p/20"), LogTiles.bundle(other));
        Files.write(dir.resolve("tile/0/000.p/20"), LogTiles.tile(other.stream().map(MerkleTree::leafHash).toList()));
        assertThat(TileCli.verify(dir, keys.getPublic(), null).problems()).singleElement().asString()
                .endsWith("not the checkpoint root");
    }

    @Test
    void anotherKeyAndMissingTilesFail() throws Exception {
        publish(entries(20));
        TileCli.archive(serve() + "/", dir, HttpClient.newHttpClient());
        assertThat(TileCli.verify(dir, SigningKeys.generate().getPublic(), null).problems())
                .containsExactly("The checkpoint is not signed by the given ledger key");
        Files.delete(dir.resolve("tile/0/000.p/20"));
        assertThat(run("tiles", "--public-key", key(), dir.toString())).isEqualTo(VerifierCli.INVALID);
    }

    @Test
    void packageLinksAreFoundInTheArchive() throws Exception {
        List<byte[]> entries = entries(20);
        publish(entries);
        TileCli.archive(serve() + "/", dir, HttpClient.newHttpClient());
        JsonMapper json = JsonMapper.builder().build();
        JsonNode pkg = json.readTree("""
                {"links": [{"sequence": 3, "hash": "%s"}, {"sequence": 25, "hash": "00"}]}"""
                .formatted(HexFormat.of().formatHex(entries.get(2))));
        TileCli.TileReport report = TileCli.verify(dir, keys.getPublic(), pkg);
        assertThat(report.valid()).isTrue();
        assertThat(report.packageLinks()).isEqualTo(1);
        assertThat(report.packageLinksBeyondArchive()).isEqualTo(1);
        JsonNode forged = json.readTree("{\"links\": [{\"sequence\": 4, \"hash\": \"%s\"}]}"
                .formatted(HexFormat.of().formatHex(entries.get(2))));
        assertThat(TileCli.verify(dir, keys.getPublic(), forged).problems())
                .containsExactly("The package entry at sequence 4 differs from the archived log");
    }

    @Test
    void anArchiveDirectoryIsItselfAMirror(@TempDir Path copy) throws Exception {
        publish(entries(300));
        TileCli.archive(serve() + "/", dir, HttpClient.newHttpClient());
        HttpServer mirror = SimpleFileServer.createFileServer(new InetSocketAddress("127.0.0.1", 0),
                dir.toAbsolutePath(), SimpleFileServer.OutputLevel.NONE);
        mirror.start();
        try {
            TileCli.archive("http://127.0.0.1:" + mirror.getAddress().getPort() + "/", copy, HttpClient.newHttpClient());
        } finally {
            mirror.stop(0);
        }
        assertThat(TileCli.verify(copy, keys.getPublic(), null).valid()).isTrue();
    }
}
