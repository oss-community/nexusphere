package com.nexusphere.ledger.e2e.transparency;

import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.LogTiles;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import com.nexusphere.ledger.verifier.VerifierCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class TileE2ETest extends LedgerE2ETestBase {

    @TempDir
    Path dir;

    private String run(int expected, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = VerifierCli.run(args, new PrintStream(out), new PrintStream(err));
        assertThat(code).as(out + "\n" + err).isEqualTo(expected);
        return out.toString(StandardCharsets.UTF_8);
    }

    private String publicKey() {
        return ledger().get("/api/v1/keys").json().get(0).path("publicKey").asString();
    }

    @Test
    void tilesAreServedPubliclyAndMatchTheSignedCheckpoint() {
        JsonNode entry = ledger().recordEvidence(toolCall(unique("agent"), "alice", "read_file"));
        ledger().post("/api/v1/checkpoints");
        LogCheckpoint checkpoint = LogCheckpoint.parse(anonymous().get("/public/v1/log/checkpoint").body())
                .checkpoint();
        long sequence = entry.path("sequence").asLong();
        long index = (sequence - 1) / LogTiles.WIDTH;
        int width = (int) Math.min(LogTiles.WIDTH, checkpoint.size() - index * LogTiles.WIDTH);

        LedgerClient.Response leaves = anonymous().get("/public/v1/log/"
                + new LogTiles.Tile(0, index, width).path());
        byte[] bundle = anonymous().getBytes("/public/v1/log/" + new LogTiles.Tile(LogTiles.ENTRIES, index, width)
                .path());

        assertThat(leaves.status()).isEqualTo(200);
        assertThat(leaves.header("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("immutable"));
        int at = (int) ((sequence - 1) % LogTiles.WIDTH);
        byte[] entryHash = LogTiles.entries(bundle).get(at);
        assertThat(HexFormat.of().formatHex(entryHash)).isEqualTo(entry.path("hash").asString());
        assertThat(anonymous().getBytes("/public/v1/log/" + new LogTiles.Tile(0, index, width).path()))
                .hasSize(width * 32);
        assertThat(LogTiles.hashes(anonymous().getBytes("/public/v1/log/" + new LogTiles.Tile(0, index, width)
                .path())).get(at)).isEqualTo(MerkleTree.leafHash(entryHash));
        assertThat(anonymous().get("/public/v1/log/tile/0/9" + "99.p/9").status()).isEqualTo(404);
        assertThat(anonymous().get("/public/v1/log/tile/0/5").status()).isEqualTo(404);
    }

    @Test
    void anArchiveIsMirroredAndVerifiedOffline() throws Exception {
        String agent = unique("agent");
        ledger().recordEvidence(toolCall(agent, "carol", "read_file"));
        ledger().recordEvidence(toolCall(agent, "carol", "send_email"));
        ledger().post("/api/v1/checkpoints");
        String url = baseUrl() + "/public/v1/log";

        run(VerifierCli.VALID, "archive", url, dir.toString());
        String report = run(VerifierCli.VALID, "tiles", "--public-key", publicKey(), "--json", dir.toString());
        JsonNode first = JsonMapper.builder().build().readTree(report);
        assertThat(first.path("valid").asBoolean()).isTrue();

        ledger().recordEvidence(toolCall(agent, "carol", "write_file"));
        ledger().post("/api/v1/checkpoints");
        JsonNode archived = JsonMapper.builder().build().readTree(run(VerifierCli.VALID, "archive", "--json", url,
                dir.toString()));
        assertThat(archived.path("treeSize").asLong()).isGreaterThan(first.path("treeSize").asLong());

        Path pkg = dir.resolveSibling(dir.getFileName() + "-package.json");
        String exported = ledger().post("/api/v1/packages", "{\"agentId\": \"" + agent + "\"}").body();
        Files.writeString(pkg, exported);
        long links = JsonMapper.builder().build().readTree(exported).path("links").size();
        JsonNode verified = JsonMapper.builder().build().readTree(run(VerifierCli.VALID, "tiles", "--public-key",
                publicKey(), "--package", pkg.toString(), "--json", dir.toString()));
        assertThat(links).isGreaterThanOrEqualTo(3);
        assertThat(verified.path("packageLinks").asLong()).isEqualTo(links);
        assertThat(verified.path("entries").asLong()).isEqualTo(archived.path("treeSize").asLong());
    }
}
