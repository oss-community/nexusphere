package com.nexusphere.ledger.transparency.infrastructure.persistence;

import com.nexusphere.ledger.transparency.domain.repository.LogCheckpointRepository;
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
class JdbcLogCheckpointRepository implements LogCheckpointRepository {

    private static final String SELECT = "select tree_size, root_hash, note, created_at from ledger.log_checkpoint";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcLogCheckpointRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(StoredCheckpoint checkpoint) {
        jdbc.update("""
                insert into ledger.log_checkpoint (tree_size, root_hash, note, created_at)
                values (:size, :root, :note, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("size", checkpoint.size())
                .addValue("root", checkpoint.root())
                .addValue("note", checkpoint.note())
                .addValue("createdAt", Timestamp.from(checkpoint.createdAt())));
    }

    @Override
    public Optional<StoredCheckpoint> latest() {
        return jdbc.query(SELECT + " order by tree_size desc limit 1", Map.of(), JdbcLogCheckpointRepository::row)
                .stream().findFirst();
    }

    @Override
    public Optional<StoredCheckpoint> find(long size) {
        return jdbc.query(SELECT + " where tree_size = :size", Map.of("size", size),
                JdbcLogCheckpointRepository::row).stream().findFirst();
    }

    @Override
    public List<StoredCheckpoint> all() {
        return jdbc.query(SELECT + " order by tree_size", Map.of(), JdbcLogCheckpointRepository::row);
    }

    @Override
    public void addCosignature(Cosignature cosignature) {
        jdbc.update("""
                insert into ledger.log_cosignature (tree_size, witness, line, created_at)
                values (:size, :witness, :line, :createdAt)
                on conflict (tree_size, witness) do nothing
                """, new MapSqlParameterSource()
                .addValue("size", cosignature.size())
                .addValue("witness", cosignature.witness())
                .addValue("line", cosignature.line())
                .addValue("createdAt", Timestamp.from(cosignature.createdAt())));
    }

    @Override
    public List<Cosignature> cosignatures(long size) {
        return jdbc.query("""
                select tree_size, witness, line, created_at from ledger.log_cosignature
                where tree_size = :size order by witness
                """, Map.of("size", size), (rs, row) -> new Cosignature(rs.getLong("tree_size"),
                rs.getString("witness"), rs.getString("line"), rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public Optional<Long> lastCosignedSize(String witness) {
        return Optional.ofNullable(jdbc.queryForObject(
                "select max(tree_size) from ledger.log_cosignature where witness = :witness",
                Map.of("witness", witness), Long.class));
    }

    private static StoredCheckpoint row(ResultSet rs, int row) throws SQLException {
        return new StoredCheckpoint(rs.getLong("tree_size"), rs.getBytes("root_hash"), rs.getString("note"),
                rs.getTimestamp("created_at").toInstant());
    }
}
