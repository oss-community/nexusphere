package com.nexusphere.ledger.evidence.infrastructure.persistence;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.evidence.domain.repository.CheckpointRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
class JdbcCheckpointRepository implements CheckpointRepository {

    private static final String SELECT = "select sequence, head_hash, created_at, key_id, signature from ledger.checkpoint";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCheckpointRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(SignedCheckpoint signed) {
        Checkpoint checkpoint = signed.checkpoint();
        jdbc.update("""
                insert into ledger.checkpoint (sequence, head_hash, created_at, key_id, signature)
                values (:sequence, :headHash, :createdAt, :keyId, :signature)
                """, new MapSqlParameterSource()
                .addValue("sequence", checkpoint.sequence())
                .addValue("headHash", checkpoint.headHash())
                .addValue("createdAt", Timestamp.from(checkpoint.createdAt()))
                .addValue("keyId", checkpoint.keyId())
                .addValue("signature", signed.signature()));
    }

    @Override
    public Optional<SignedCheckpoint> latest() {
        return jdbc.query(SELECT + " order by sequence desc limit 1", Map.of(), JdbcCheckpointRepository::row)
                .stream().findFirst();
    }

    @Override
    public Optional<SignedCheckpoint> findBySequence(long sequence) {
        return jdbc.query(SELECT + " where sequence = :sequence", Map.of("sequence", sequence),
                JdbcCheckpointRepository::row).stream().findFirst();
    }

    @Override
    public List<SignedCheckpoint> list(long afterSequence, int limit) {
        return jdbc.query(SELECT + " where sequence > :after order by sequence limit :limit",
                Map.of("after", afterSequence, "limit", limit), JdbcCheckpointRepository::row);
    }

    @Override
    public List<SignedCheckpoint> between(long afterSequence, long upToSequence) {
        return jdbc.query(SELECT + " where sequence > :after and sequence <= :upTo order by sequence",
                Map.of("after", afterSequence, "upTo", upToSequence), JdbcCheckpointRepository::row);
    }

    @Override
    public Optional<SignedCheckpoint> firstAtOrAfter(long sequence) {
        return jdbc.query(SELECT + " where sequence >= :sequence order by sequence limit 1",
                Map.of("sequence", sequence), JdbcCheckpointRepository::row).stream().findFirst();
    }

    @Override
    public Optional<SignedCheckpoint> lastBefore(long sequence) {
        return jdbc.query(SELECT + " where sequence < :sequence order by sequence desc limit 1",
                Map.of("sequence", sequence), JdbcCheckpointRepository::row).stream().findFirst();
    }

    private static SignedCheckpoint row(ResultSet rs, int row) throws SQLException {
        Checkpoint checkpoint = new Checkpoint(rs.getLong("sequence"), rs.getString("head_hash"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("key_id"));
        return new SignedCheckpoint(checkpoint, rs.getString("signature"));
    }
}
