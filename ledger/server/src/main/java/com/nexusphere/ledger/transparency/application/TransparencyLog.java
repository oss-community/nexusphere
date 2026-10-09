package com.nexusphere.ledger.transparency.application;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.transparency.domain.repository.LogCheckpointRepository;
import com.nexusphere.ledger.transparency.domain.repository.LogCheckpointRepository.StoredCheckpoint;
import com.nexusphere.ledger.transparency.domain.repository.MerkleNodeRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TransparencyLog {

    private final MerkleNodeRepository nodes;
    private final LogCheckpointRepository checkpoints;
    private final LedgerSigner signer;
    private final String origin;
    private final MerkleTree.Subtrees subtrees;

    TransparencyLog(MerkleNodeRepository nodes, LogCheckpointRepository checkpoints, LedgerSigner signer,
                    LedgerProperties properties) {
        this.nodes = nodes;
        this.checkpoints = checkpoints;
        this.signer = signer;
        this.origin = origin(properties);
        this.subtrees = (level, index) -> nodes.find(level, index).orElseThrow(() -> new IllegalStateException(
                "The transparency log has no node at level " + level + " index " + index));
    }

    static String origin(LedgerProperties properties) {
        LedgerProperties.Log log = properties.log();
        if (log != null && log.origin() != null && !log.origin().isBlank()) {
            return log.origin().strip();
        }
        URI issuer = URI.create(properties.mandate().issuer());
        String path = issuer.getPath() == null ? "" : issuer.getPath().replaceAll("/+$", "");
        return issuer.getAuthority() + path;
    }

    public String origin() {
        return origin;
    }

    public NoteKey noteKey() {
        return new NoteKey(origin, NoteKey.ED25519, signer.publicKey().publicKey());
    }

    public NoteKey witnessKey() {
        return new NoteKey(origin, NoteKey.COSIGNATURE, signer.publicKey().publicKey());
    }

    public void append(EvidenceEntry entry) {
        long index = entry.sequence() - 1;
        byte[] hash = MerkleTree.leafHash(leaf(entry));
        int level = 0;
        nodes.put(level, index, hash);
        while ((index & 1) == 1) {
            byte[] left = subtrees.perfect(level, index - 1);
            hash = MerkleTree.nodeHash(left, hash);
            level++;
            index >>= 1;
            nodes.put(level, index, hash);
        }
    }

    public static byte[] leaf(EvidenceEntry entry) {
        return HexFormat.of().parseHex(entry.hash());
    }

    public byte[] root(long size) {
        return MerkleTree.root(subtrees, size);
    }

    public List<byte[]> inclusionProof(long index, long size) {
        return MerkleTree.inclusionProof(subtrees, index, size);
    }

    public Map<Long, List<byte[]>> inclusionProofs(List<Long> sequences, long size) {
        Map<String, byte[]> cache = new HashMap<>();
        MerkleTree.Subtrees cached = (level, index) -> cache.computeIfAbsent(level + ":" + index,
                key -> subtrees.perfect(level, index));
        Map<Long, List<byte[]>> proofs = new LinkedHashMap<>();
        for (long sequence : sequences) {
            proofs.put(sequence, MerkleTree.inclusionProof(cached, sequence - 1, size));
        }
        return proofs;
    }

    public List<byte[]> consistencyProof(long first, long second) {
        return MerkleTree.consistencyProof(subtrees, first, second);
    }

    public StoredCheckpoint checkpoint(long size, Instant now) {
        Optional<StoredCheckpoint> existing = checkpoints.find(size);
        if (existing.isPresent()) {
            return existing.get();
        }
        byte[] root = root(size);
        LogCheckpoint.Note note = signer.sign(new LogCheckpoint(origin, size, root));
        StoredCheckpoint stored = new StoredCheckpoint(size, root, note.text(), now);
        checkpoints.append(stored);
        return stored;
    }

    public Optional<StoredCheckpoint> latest() {
        return checkpoints.latest();
    }

    public Optional<StoredCheckpoint> find(long size) {
        return checkpoints.find(size);
    }

    public String cosignedNote(StoredCheckpoint checkpoint) {
        StringBuilder note = new StringBuilder(checkpoint.note());
        checkpoints.cosignatures(checkpoint.size()).forEach(cosignature -> note.append(cosignature.line()));
        return note.toString();
    }
}
