package com.nexusphere.ledger.chain;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LogTiles {

    public static final int HEIGHT = 8;
    public static final int WIDTH = 256;
    public static final int ENTRIES = -1;

    public record Tile(int level, long index, int width) {

        public Tile {
            if (level < ENTRIES || level > 63 || index < 0 || width < 1 || width > WIDTH) {
                throw new IllegalArgumentException("Not a tile: level " + level + ", index " + index + ", width "
                        + width);
            }
        }

        public boolean full() {
            return width == WIDTH;
        }

        public boolean entries() {
            return level == ENTRIES;
        }

        public String path() {
            String prefix = entries() ? "tile/entries/" : "tile/" + level + "/";
            return prefix + LogTiles.index(index) + (full() ? "" : ".p/" + width);
        }
    }

    private static final Pattern PATH = Pattern.compile("tile/(entries|\\d{1,2})/((?:x\\d{3}/)*\\d{3})(?:\\.p/(\\d{1,3}))?");

    private LogTiles() {
    }

    public static String index(long index) {
        String digits = String.valueOf(index);
        int padded = (digits.length() + 2) / 3 * 3;
        digits = "0".repeat(padded - digits.length()) + digits;
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < digits.length(); i += 3) {
            if (i > 0) {
                path.append('/');
            }
            if (i + 3 < digits.length()) {
                path.append('x');
            }
            path.append(digits, i, i + 3);
        }
        return path.toString();
    }

    public static Optional<Tile> parse(String path) {
        Matcher matcher = PATH.matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int level = matcher.group(1).equals("entries") ? ENTRIES : Integer.parseInt(matcher.group(1));
        String index = matcher.group(2).replace("x", "").replace("/", "");
        if (index.length() > 18) {
            return Optional.empty();
        }
        int width = matcher.group(3) == null ? WIDTH : Integer.parseInt(matcher.group(3));
        if (width < 1 || width >= WIDTH && matcher.group(3) != null) {
            return Optional.empty();
        }
        try {
            Tile tile = new Tile(level, Long.parseLong(index), width);
            return tile.path().equals(path) ? Optional.of(tile) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static List<Tile> tiles(long size) {
        List<Tile> tiles = new ArrayList<>();
        long nodes = size;
        for (int level = 0; nodes > 0; level++) {
            for (long index = 0; index * WIDTH < nodes; index++) {
                tiles.add(new Tile(level, index, (int) Math.min(WIDTH, nodes - index * WIDTH)));
            }
            nodes >>= HEIGHT;
        }
        for (long index = 0; index * WIDTH < size; index++) {
            tiles.add(new Tile(ENTRIES, index, (int) Math.min(WIDTH, size - index * WIDTH)));
        }
        return tiles;
    }

    public static byte[] bundle(List<byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] entry : entries) {
            if (entry.length > 0xffff) {
                throw new IllegalArgumentException("An entry is longer than 65535 bytes");
            }
            out.write(entry.length >> 8);
            out.write(entry.length & 0xff);
            out.writeBytes(entry);
        }
        return out.toByteArray();
    }

    public static List<byte[]> entries(byte[] bundle) {
        List<byte[]> entries = new ArrayList<>();
        int at = 0;
        while (at < bundle.length) {
            if (at + 2 > bundle.length) {
                throw new IllegalArgumentException("The entry bundle ends inside a length");
            }
            int length = (bundle[at] & 0xff) << 8 | bundle[at + 1] & 0xff;
            if (at + 2 + length > bundle.length) {
                throw new IllegalArgumentException("The entry bundle ends inside an entry");
            }
            byte[] entry = new byte[length];
            System.arraycopy(bundle, at + 2, entry, 0, length);
            entries.add(entry);
            at += 2 + length;
        }
        return entries;
    }

    public static List<byte[]> hashes(byte[] tile) {
        if (tile.length % 32 != 0) {
            throw new IllegalArgumentException("A hash tile must hold whole 32-byte hashes");
        }
        List<byte[]> hashes = new ArrayList<>();
        for (int at = 0; at < tile.length; at += 32) {
            byte[] hash = new byte[32];
            System.arraycopy(tile, at, hash, 0, 32);
            hashes.add(hash);
        }
        return hashes;
    }

    public static byte[] tile(List<byte[]> hashes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        hashes.forEach(out::writeBytes);
        return out.toByteArray();
    }
}
