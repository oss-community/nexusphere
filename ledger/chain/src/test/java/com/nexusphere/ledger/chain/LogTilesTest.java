package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogTilesTest {

    @Test
    void indexesAreSplitIntoThreeDigitElements() {
        assertThat(LogTiles.index(0)).isEqualTo("000");
        assertThat(LogTiles.index(7)).isEqualTo("007");
        assertThat(LogTiles.index(1000)).isEqualTo("x001/000");
        assertThat(LogTiles.index(1234067)).isEqualTo("x001/x234/067");
    }

    @Test
    void pathsRoundTrip() {
        for (LogTiles.Tile tile : List.of(new LogTiles.Tile(0, 1234067, 256), new LogTiles.Tile(2, 0, 3),
                new LogTiles.Tile(LogTiles.ENTRIES, 5, 255))) {
            assertThat(LogTiles.parse(tile.path())).contains(tile);
        }
        assertThat(new LogTiles.Tile(0, 1234067, 256).path()).isEqualTo("tile/0/x001/x234/067");
        assertThat(new LogTiles.Tile(LogTiles.ENTRIES, 5, 17).path()).isEqualTo("tile/entries/005.p/17");
    }

    @Test
    void nonCanonicalPathsAreRejected() {
        assertThat(LogTiles.parse("tile/0/5")).isEmpty();
        assertThat(LogTiles.parse("tile/0/x000/005")).isEmpty();
        assertThat(LogTiles.parse("tile/0/005.p/256")).isEmpty();
        assertThat(LogTiles.parse("tile/0/005.p/0")).isEmpty();
        assertThat(LogTiles.parse("tile/0/005.p/007")).isEmpty();
        assertThat(LogTiles.parse("tile/data/005")).isEmpty();
        assertThat(LogTiles.parse("../tile/0/005")).isEmpty();
    }

    @Test
    void aTreeSizeListsEveryTile() {
        assertThat(LogTiles.tiles(0)).isEmpty();
        assertThat(LogTiles.tiles(3)).extracting(LogTiles.Tile::path)
                .containsExactly("tile/0/000.p/3", "tile/entries/000.p/3");
        assertThat(LogTiles.tiles(70000)).extracting(LogTiles.Tile::path)
                .contains("tile/0/272", "tile/0/273.p/112", "tile/1/000", "tile/1/001.p/17", "tile/2/000.p/1",
                        "tile/entries/273.p/112")
                .hasSize(274 + 2 + 1 + 274);
    }

    @Test
    void bundlesRoundTrip() {
        List<byte[]> entries = List.of("a".getBytes(StandardCharsets.UTF_8), new byte[0], new byte[300]);
        byte[] bundle = LogTiles.bundle(entries);
        assertThat(bundle).hasSize(2 + 1 + 2 + 2 + 300);
        assertThat(LogTiles.entries(bundle)).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(entries);
        assertThatThrownBy(() -> LogTiles.entries(new byte[]{0, 5, 1})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashTilesHoldWholeHashes() {
        byte[] tile = LogTiles.tile(List.of(new byte[32], new byte[32]));
        assertThat(LogTiles.hashes(tile)).hasSize(2);
        assertThatThrownBy(() -> LogTiles.hashes(new byte[33])).isInstanceOf(IllegalArgumentException.class);
    }
}
