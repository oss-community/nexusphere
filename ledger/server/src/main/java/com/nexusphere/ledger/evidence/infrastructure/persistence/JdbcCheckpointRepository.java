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
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
class JdbcCheckpointRepository implements CheckpointRepository {

    private static final String SELECT = """
            select c.sequence, c.head_hash, c.created_at, c.key_id, c.signature, c.profiles, t.token
            from ledger.checkpoint c left join ledger.checkpoint_timestamp t on t.sequence = c.sequence""";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCheckpointRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(SignedCheckpoint signed) {
        Checkpoint checkpoint = signed.checkpoint();
        jdbc.update("""
                insert into ledger.checkpoint (sequence, head_hash, created_at, key_id, signature, profiles)
                values (:sequence, :headHash, :createdAt, :keyId, :signature, :profiles)
                """, new MapSqlParameterSource()
                .addValue("sequence", checkpoint.sequence())
                .addValue("headHash", checkpoint.headHash())
                .addValue("createdAt", Timestamp.from(checkpoint.createdAt()))
                .addValue("keyId", checkpoint.keyId())
                .addValue("signature", signed.signature())
                .addValue("profiles", checkpoint.profiles() == null ? null : checkpoint.profiles().stream()
                        .map(p -> p.id() + ":" + p.digest()).collect(Collectors.joining(","))));
    }

    @Override
    public void appendTimestamp(long sequence, byte[] token, Instant genTime, Instant recordedAt) {
        jdbc.update("""
                insert into ledger.checkpoint_timestamp (sequence, token, gen_time, recorded_at)
                values (:sequence, :token, :genTime, :recordedAt) on conflict (sequence) do nothing
                """, new MapSqlParameterSource("sequence", sequence).addValue("token", token)
                .addValue("genTime", Timestamp.from(genTime)).addValue("recordedAt", Timestamp.from(recordedAt)));
    }

    @Override
    public List<SignedCheckpoint> withoutTimestamp(int limit) {
        return jdbc.query(SELECT + " where t.sequence is null order by c.sequence desc limit :limit",
                Map.of("limit", limit), JdbcCheckpointRepository::row);
    }

    @Override
    public Optional<SignedCheckpoint> latest() {
        return jdbc.query(SELECT + " order by c.sequence desc limit 1", Map.of(), JdbcCheckpointRepository::row)
                .stream().findFirst();
    }

    @Override
    public Optional<SignedCheckpoint> findBySequence(long sequence) {
        return jdbc.query(SELECT + " where c.sequence = :sequence", Map.of("sequence", sequence),
                JdbcCheckpointRepository::row).stream().findFirst();
    }

    @Override
    public List<SignedCheckpoint> list(long afterSequence, int limit) {
        return jdbc.query(SELECT + " where c.sequence > :after order by c.sequence limit :limit",
                Map.of("after", afterSequence, "limit", limit), JdbcCheckpointRepository::row);
    }

    @Override
    public List<SignedCheckpoint> between(long afterSequence, long upToSequence) {
        return jdbc.query(SELECT + " where c.sequence > :after and c.sequence <= :upTo order by c.sequence",
                Map.of("after", afterSequence, "upTo", upToSequence), JdbcCheckpointRepository::row);
    }

    @Override
    public Optional<SignedCheckpoint> firstAtOrAfter(long sequence) {
        return jdbc.query(SELECT + " where c.sequence >= :sequence order by c.sequence limit 1",
                Map.of("sequence", sequence), JdbcCheckpointRepository::row).stream().findFirst();
    }

    @Override
    public Optional<SignedCheckpoint> lastBefore(long sequence) {
        return jdbc.query(SELECT + " where c.sequence < :sequence order by c.sequence desc limit 1",
                Map.of("sequence", sequence), JdbcCheckpointRepository::row).stream().findFirst();
    }

    private static SignedCheckpoint row(ResultSet rs, int row) throws SQLException {
        Checkpoint checkpoint = new Checkpoint(rs.getLong("sequence"), rs.getString("head_hash"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("key_id"), profiles(rs.getString("profiles")));
        byte[] token = rs.getBytes("token");
        return new SignedCheckpoint(checkpoint, rs.getString("signature"),
                token == null ? null : Base64.getEncoder().encodeToString(token));
    }

    private static List<Checkpoint.Profile> profiles(String stored) {
        if (stored == null) {
            return null;
        }
        return Arrays.stream(stored.split(",")).filter(p -> !p.isEmpty()).map(p -> p.split(":", 2))
                .map(p -> new Checkpoint.Profile(p[0], p[1])).toList();
    }
}
