package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.LogTiles;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.SigningKeys;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

final class TileCli {

    record ArchiveReport(String origin, long treeSize, long fetched, long kept, long removed) {
    }

    record TileReport(boolean valid, String origin, long treeSize, String rootHash, long tiles, long entries,
                      long packageLinks, long packageLinksBeyondArchive, List<String> problems) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final String CHECKPOINT = "checkpoint";

    private TileCli() {
    }

    static int archive(String[] args, PrintStream out, PrintStream err, String usage) {
        boolean json = false;
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if ("--json".equals(arg)) {
                json = true;
            } else if (arg.startsWith("--")) {
                err.println(usage);
                return VerifierCli.USAGE;
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() != 2) {
            err.println(usage);
            return VerifierCli.USAGE;
        }
        String base = positional.get(0).endsWith("/") ? positional.get(0) : positional.get(0) + "/";
        ArchiveReport report;
        try {
            report = archive(base, Path.of(positional.get(1)), HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build());
        } catch (IOException | IllegalArgumentException e) {
            err.println("Cannot archive " + base + ": " + e.getMessage());
            return VerifierCli.INVALID;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Interrupted while archiving " + base);
            return VerifierCli.INVALID;
        }
        if (json) {
            out.println(JSON.writeValueAsString(report));
        } else {
            out.println("Nexusphere Ledger log archive");
            out.println("  Origin      : " + report.origin());
            out.println("  Tree size   : " + report.treeSize());
            out.println("  Fetched     : " + report.fetched() + " tiles");
            out.println("  Kept        : " + report.kept() + " tiles already archived");
            out.println("  Removed     : " + report.removed() + " superseded partial tiles");
        }
        return VerifierCli.VALID;
    }

    static ArchiveReport archive(String base, Path dir, HttpClient http) throws IOException, InterruptedException {
        String text = new String(fetch(http, base + CHECKPOINT), StandardCharsets.UTF_8);
        LogCheckpoint checkpoint = LogCheckpoint.parse(text).checkpoint();
        Files.createDirectories(dir);
        List<LogTiles.Tile> tiles = LogTiles.tiles(checkpoint.size());
        long fetched = 0;
        long kept = 0;
        Set<Path> wanted = new HashSet<>();
        for (LogTiles.Tile tile : tiles) {
            Path file = dir.resolve(tile.path());
            wanted.add(file.normalize());
            if (Files.isRegularFile(file)) {
                kept++;
                continue;
            }
            byte[] body = fetch(http, base + tile.path());
            Files.createDirectories(file.getParent());
            Path partial = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(partial, body);
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
            fetched++;
        }
        long removed = 0;
        Path tileRoot = dir.resolve("tile");
        if (Files.isDirectory(tileRoot)) {
            List<Path> stale;
            try (Stream<Path> walk = Files.walk(tileRoot)) {
                stale = walk.filter(Files::isRegularFile)
                        .filter(path -> dir.relativize(path).toString().replace('\\', '/').contains(".p/"))
                        .filter(path -> !wanted.contains(path.normalize())).toList();
            }
            for (Path path : stale) {
                Files.delete(path);
                removed++;
            }
        }
        Files.writeString(dir.resolve(CHECKPOINT), text);
        return new ArchiveReport(checkpoint.origin(), checkpoint.size(), fetched, kept, removed);
    }

    private static byte[] fetch(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException(url + " answered " + response.statusCode());
        }
        return response.body();
    }

    static int verify(String[] args, PrintStream out, PrintStream err, String usage) {
        String publicKey = null;
        Path packageFile = null;
        boolean json = false;
        Path dir = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--public-key" -> publicKey = args[++i].trim();
                    case "--public-key-file" -> publicKey = Files.readString(Path.of(args[++i])).trim();
                    case "--package" -> packageFile = Path.of(args[++i]);
                    case "--json" -> json = true;
                    default -> {
                        if (dir != null || args[i].startsWith("--")) {
                            err.println(usage);
                            return VerifierCli.USAGE;
                        }
                        dir = Path.of(args[i]);
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException | IOException e) {
            err.println(usage);
            return VerifierCli.USAGE;
        }
        if (dir == null || publicKey == null) {
            err.println(usage);
            return VerifierCli.USAGE;
        }
        PublicKey key;
        JsonNode pkg;
        try {
            key = SigningKeys.decodePublic(publicKey);
            pkg = packageFile == null ? null : JSON.readTree(Files.readAllBytes(packageFile));
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            err.println("Cannot read the key or package: " + e.getMessage());
            return VerifierCli.USAGE;
        }
        TileReport report = verify(dir, key, pkg);
        if (json) {
            out.println(JSON.writeValueAsString(report));
        } else {
            out.println("Nexusphere Ledger log archive verification");
            out.println("  Result      : " + (report.valid() ? "VALID" : "INVALID"));
            out.println("  Origin      : " + report.origin());
            out.println("  Tree size   : " + report.treeSize());
            out.println("  Root hash   : " + report.rootHash());
            out.println("  Tiles       : " + report.tiles());
            out.println("  Entries     : " + report.entries());
            if (pkg != null) {
                out.println("  Package     : " + report.packageLinks() + " links found in the archive, "
                        + report.packageLinksBeyondArchive() + " beyond it");
            }
            report.problems().forEach(problem -> out.println("  Problem     : " + problem));
        }
        return report.valid() ? VerifierCli.VALID : VerifierCli.INVALID;
    }

    static TileReport verify(Path dir, PublicKey key, JsonNode pkg) {
        List<String> problems = new ArrayList<>();
        LogCheckpoint.Note note;
        try {
            note = LogCheckpoint.parse(Files.readString(dir.resolve(CHECKPOINT)));
        } catch (IOException | IllegalArgumentException e) {
            return new TileReport(false, null, 0, null, 0, 0, 0, 0,
                    List.of("The archive has no readable checkpoint: " + e.getMessage()));
        }
        LogCheckpoint checkpoint = note.checkpoint();
        if (!note.signedBy(new NoteKey(checkpoint.origin(), NoteKey.ED25519, key))) {
            problems.add("The checkpoint is not signed by the given ledger key");
        }
        long size = checkpoint.size();
        List<byte[]> leaves = new ArrayList<>();
        List<byte[]> entries = new ArrayList<>();
        List<LogTiles.Tile> tiles = LogTiles.tiles(size);
        long read = 0;
        for (LogTiles.Tile tile : tiles) {
            if (tile.level() > 0) {
                continue;
            }
            byte[] body = read(dir, tile, problems);
            if (body == null) {
                return failed(checkpoint, read, problems);
            }
            read++;
            try {
                List<byte[]> items = tile.entries() ? LogTiles.entries(body) : LogTiles.hashes(body);
                if (items.size() != tile.width()) {
                    problems.add(tile.path() + " holds " + items.size() + " items, not " + tile.width());
                    return failed(checkpoint, read, problems);
                }
                (tile.entries() ? entries : leaves).addAll(items);
            } catch (IllegalArgumentException e) {
                problems.add(tile.path() + ": " + e.getMessage());
                return failed(checkpoint, read, problems);
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            if (!Arrays.equals(MerkleTree.leafHash(entries.get(i)), leaves.get(i))) {
                problems.add("Entry " + (i + 1) + " does not hash to its leaf in tile/0");
            }
        }
        MerkleTree.Subtrees subtrees = MerkleTree.of(leaves);
        for (LogTiles.Tile tile : tiles) {
            if (tile.level() < 1) {
                continue;
            }
            byte[] body = read(dir, tile, problems);
            if (body == null) {
                return failed(checkpoint, read, problems);
            }
            read++;
            List<byte[]> hashes;
            try {
                hashes = LogTiles.hashes(body);
            } catch (IllegalArgumentException e) {
                problems.add(tile.path() + ": " + e.getMessage());
                continue;
            }
            if (hashes.size() != tile.width()) {
                problems.add(tile.path() + " holds " + hashes.size() + " hashes, not " + tile.width());
                continue;
            }
            int level = tile.level() * LogTiles.HEIGHT;
            for (int j = 0; j < hashes.size(); j++) {
                if (!Arrays.equals(hashes.get(j), subtrees.perfect(level, tile.index() * LogTiles.WIDTH + j))) {
                    problems.add(tile.path() + " hash " + j + " does not match the level 0 tiles");
                    break;
                }
            }
        }
        byte[] root = MerkleTree.root(subtrees, size);
        if (!Arrays.equals(root, checkpoint.root())) {
            problems.add("The tiles hash to " + Base64.getEncoder().encodeToString(root)
                    + ", not the checkpoint root");
        }
        long found = 0;
        long beyond = 0;
        if (pkg != null) {
            for (JsonNode link : pkg.path("links")) {
                long sequence = link.path("sequence").asLong();
                if (sequence < 1) {
                    problems.add("The package has a link without a sequence");
                } else if (sequence > size) {
                    beyond++;
                } else if (HexFormat.of().formatHex(entries.get(Math.toIntExact(sequence - 1)))
                        .equalsIgnoreCase(link.path("hash").asString(""))) {
                    found++;
                } else {
                    problems.add("The package entry at sequence " + sequence + " differs from the archived log");
                }
            }
        }
        return new TileReport(problems.isEmpty(), checkpoint.origin(), size, Base64.getEncoder()
                .encodeToString(checkpoint.root()), read, entries.size(), found, beyond, List.copyOf(problems));
    }

    private static byte[] read(Path dir, LogTiles.Tile tile, List<String> problems) {
        try {
            return Files.readAllBytes(dir.resolve(tile.path()));
        } catch (IOException e) {
            problems.add("The archive is missing " + tile.path());
            return null;
        }
    }

    private static TileReport failed(LogCheckpoint checkpoint, long read, List<String> problems) {
        return new TileReport(false, checkpoint.origin(), checkpoint.size(),
                Base64.getEncoder().encodeToString(checkpoint.root()), read, 0, 0, 0, List.copyOf(problems));
    }
}
