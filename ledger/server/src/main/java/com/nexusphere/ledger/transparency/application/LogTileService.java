package com.nexusphere.ledger.transparency.application;

import com.nexusphere.ledger.chain.LogTiles;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import com.nexusphere.ledger.transparency.domain.repository.MerkleNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class LogTileService {

    private final MerkleNodeRepository nodes;
    private final EvidenceRepository evidence;

    LogTileService(MerkleNodeRepository nodes, EvidenceRepository evidence) {
        this.nodes = nodes;
        this.evidence = evidence;
    }

    @Transactional(readOnly = true)
    public Optional<byte[]> tile(LogTiles.Tile tile) {
        long from = tile.index() * LogTiles.WIDTH;
        if (tile.entries()) {
            List<String> hashes = evidence.hashes(from, tile.width());
            if (hashes.size() != tile.width()) {
                return Optional.empty();
            }
            return Optional.of(LogTiles.bundle(hashes.stream().map(HexFormat.of()::parseHex).toList()));
        }
        int level = tile.level() * LogTiles.HEIGHT;
        if (level > 62) {
            return Optional.empty();
        }
        List<byte[]> hashes = nodes.range(level, from, tile.width());
        return hashes.size() == tile.width() ? Optional.of(LogTiles.tile(hashes)) : Optional.empty();
    }
}
