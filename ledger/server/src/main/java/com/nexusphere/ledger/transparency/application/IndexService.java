package com.nexusphere.ledger.transparency.application;

import com.nexusphere.ledger.chain.IndexRoot;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.chain.SparseMerkleTree;
import com.nexusphere.ledger.chain.Timestamps;
import com.nexusphere.ledger.evidence.domain.repository.CheckpointRepository;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

@Service
public class IndexService {

    public static final String PROOF_FORMAT = "nexusphere-ledger/index-proof/v1";
    private static final int BATCH = 1000;
    private static final HexFormat HEX = HexFormat.of();

    public record SignedIndex(IndexRoot root, String signature) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", IndexRoot.FORMAT);
            map.put("sequence", root.sequence());
            map.put("headHash", root.headHash());
            map.put("root", root.root());
            map.put("createdAt", Timestamps.format(root.createdAt()));
            map.put("keyId", root.keyId());
            map.put("signature", signature);
            return map;
        }
    }

    private record Leaf(long count, long first, long last) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final CheckpointRepository checkpoints;
    private final LedgerSigner signer;
    private final Clock clock;

    IndexService(NamedParameterJdbcTemplate jdbc, CheckpointRepository checkpoints, LedgerSigner signer,
                 Clock clock) {
        this.jdbc = jdbc;
        this.checkpoints = checkpoints;
        this.signer = signer;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> proof(Map<String, ?> requested, Long sequence) {
        Map<String, Object> term;
        try {
            term = IndexRoot.term(requested == null ? Map.of() : requested);
        } catch (IllegalArgumentException e) {
            throw LedgerException.invalid("The index proof request has invalid fields.",
                    Map.of("term", e.getMessage()));
        }
        SignedCheckpoint checkpoint = (sequence == null ? checkpoints.latest() : checkpoints.findBySequence(sequence))
                .orElseThrow(() -> LedgerException.notFound(sequence == null ? "A checkpoint"
                        : "Checkpoint " + sequence));
        long at = checkpoint.checkpoint().sequence();
        catchUp(at);
        Map<String, Leaf> leaves = leaves(at);
        SortedMap<String, byte[]> values = new TreeMap<>();
        leaves.forEach((key, leaf) -> values.put(key, SparseMerkleTree.valueHash(value(leaf))));
        String root = HEX.formatHex(SparseMerkleTree.root(values));
        SignedIndex index = signed(checkpoint, root);
        byte[] key = SparseMerkleTree.key(term);
        SparseMerkleTree.Proof proof = SparseMerkleTree.proof(values, key);
        Leaf leaf = leaves.get(HEX.formatHex(key));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("format", PROOF_FORMAT);
        map.put("term", term);
        map.put("key", HEX.formatHex(key));
        map.put("leaf", leaf == null ? null : value(leaf));
        map.put("siblings", proof.siblings().stream().map(HEX::formatHex).toList());
        if (proof.otherKey() == null) {
            map.put("otherLeaf", null);
        } else {
            Map<String, Object> other = new LinkedHashMap<>();
            other.put("key", HEX.formatHex(proof.otherKey()));
            other.put("valueHash", HEX.formatHex(proof.otherValueHash()));
            map.put("otherLeaf", other);
        }
        map.put("index", index.toMap());
        return map;
    }

    private SignedIndex signed(SignedCheckpoint checkpoint, String root) {
        long at = checkpoint.checkpoint().sequence();
        List<SignedIndex> existing = existing(at);
        if (existing.isEmpty()) {
            IndexRoot index = new IndexRoot(at, checkpoint.checkpoint().headHash(), root, clock.instant(),
                    signer.publicKey().keyId());
            jdbc.update("""
                    insert into ledger.index_root (sequence, head_hash, root, created_at, key_id, signature)
                    values (:sequence, :headHash, :root, :createdAt, :keyId, :signature)
                    on conflict (sequence) do nothing
                    """, new MapSqlParameterSource()
                    .addValue("sequence", at)
                    .addValue("headHash", index.headHash())
                    .addValue("root", root)
                    .addValue("createdAt", Timestamp.from(index.createdAt()))
                    .addValue("keyId", index.keyId())
                    .addValue("signature", signer.signBytes(index.signedBytes())));
            existing = existing(at);
        }
        SignedIndex index = existing.getFirst();
        if (!index.root().root().equals(root)) {
            throw new IllegalStateException("The index of checkpoint " + at + " was signed with root "
                    + index.root().root() + " but the evidence now gives " + root);
        }
        return index;
    }

    private List<SignedIndex> existing(long sequence) {
        return jdbc.query("""
                select sequence, head_hash, root, created_at, key_id, signature from ledger.index_root
                where sequence = :sequence
                """, Map.of("sequence", sequence), (rs, n) -> new SignedIndex(new IndexRoot(rs.getLong("sequence"),
                rs.getString("head_hash"), rs.getString("root"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("key_id")), rs.getString("signature")));
    }

    private void catchUp(long sequence) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext('ledger.index'))", Map.of());
        Long indexed = jdbc.queryForObject("select max(sequence) from ledger.index_entry", Map.of(), Long.class);
        long from = indexed == null ? 0 : indexed;
        while (from < sequence) {
            List<MapSqlParameterSource> rows = new ArrayList<>();
            long[] last = {from};
            jdbc.query("""
                    select sequence, agent_id, action, decision, input_hash, delegation_id from ledger.evidence_record
                    where sequence > :from and sequence <= :to order by sequence limit :limit
                    """, Map.of("from", from, "to", sequence, "limit", BATCH), rs -> {
                long at = rs.getLong("sequence");
                last[0] = at;
                for (Map<String, Object> term : IndexRoot.terms(rs.getString("agent_id"), rs.getString("action"),
                        rs.getString("decision"), rs.getString("input_hash"), rs.getString("delegation_id"))) {
                    rows.add(new MapSqlParameterSource().addValue("key", HEX.formatHex(SparseMerkleTree.key(term)))
                            .addValue("sequence", at));
                }
            });
            if (last[0] == from) {
                break;
            }
            jdbc.batchUpdate("insert into ledger.index_entry (key, sequence) values (:key, :sequence)"
                    + " on conflict do nothing", rows.toArray(MapSqlParameterSource[]::new));
            from = last[0];
        }
    }

    private Map<String, Leaf> leaves(long sequence) {
        Map<String, Leaf> leaves = new LinkedHashMap<>();
        jdbc.query("""
                select key, count(*) as count, min(sequence) as first, max(sequence) as last from ledger.index_entry
                where sequence <= :sequence group by key
                """, Map.of("sequence", sequence), rs -> {
            leaves.put(rs.getString("key"), new Leaf(rs.getLong("count"), rs.getLong("first"), rs.getLong("last")));
        });
        return leaves;
    }

    private static Map<String, Object> value(Leaf leaf) {
        return IndexRoot.value(leaf.count(), leaf.first(), leaf.last());
    }
}
