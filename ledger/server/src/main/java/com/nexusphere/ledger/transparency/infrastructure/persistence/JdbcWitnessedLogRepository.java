package com.nexusphere.ledger.transparency.infrastructure.persistence;

import com.nexusphere.ledger.transparency.domain.repository.WitnessedLogRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
class JdbcWitnessedLogRepository implements WitnessedLogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcWitnessedLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WitnessedLog lock(String origin, byte[] emptyRoot, Instant now) {
        jdbc.update("""
                insert into ledger.witnessed_log (origin, tree_size, root_hash, updated_at)
                values (:origin, 0, :root, :now)
                on conflict (origin) do nothing
                """, new MapSqlParameterSource().addValue("origin", origin).addValue("root", emptyRoot)
                .addValue("now", Timestamp.from(now)));
        return jdbc.queryForObject("""
                select origin, tree_size, root_hash, updated_at from ledger.witnessed_log
                where origin = :origin for update
                """, new MapSqlParameterSource("origin", origin), (rs, row) -> new WitnessedLog(
                rs.getString("origin"), rs.getLong("tree_size"), rs.getBytes("root_hash"),
                rs.getTimestamp("updated_at").toInstant()));
    }

    @Override
    public void update(WitnessedLog log) {
        jdbc.update("""
                update ledger.witnessed_log set tree_size = :size, root_hash = :root, updated_at = :updatedAt
                where origin = :origin
                """, new MapSqlParameterSource().addValue("origin", log.origin()).addValue("size", log.size())
                .addValue("root", log.root()).addValue("updatedAt", Timestamp.from(log.updatedAt())));
    }
}
