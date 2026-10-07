package com.nexusphere.ledger.evidence.infrastructure.persistence;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.evidence.domain.model.EvidenceQuery;
import com.nexusphere.ledger.evidence.domain.model.LedgerHead;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Repository
class JdbcEvidenceRepository implements EvidenceRepository {

    private static final String SELECT = """
            select id, sequence, occurred_at, recorded_at, agent_id, principal_id, action, target, decision, reason,
                   delegation_id, input_hash, output_hash, outcome, correlation_id, previous_hash, hash
            from ledger.evidence_record
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcEvidenceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LedgerHead lockHead() {
        return jdbc.queryForObject("select sequence, hash from ledger.ledger_head where id = 1 for update",
                Map.of(), (rs, row) -> new LedgerHead(rs.getLong("sequence"), rs.getString("hash")));
    }

    @Override
    public LedgerHead head() {
        return jdbc.queryForObject("select sequence, hash from ledger.ledger_head where id = 1",
                Map.of(), (rs, row) -> new LedgerHead(rs.getLong("sequence"), rs.getString("hash")));
    }

    @Override
    public void append(EvidenceEntry entry) {
        jdbc.update("""
                insert into ledger.evidence_record (id, sequence, occurred_at, recorded_at, agent_id, principal_id,
                    action, target, decision, reason, delegation_id, input_hash, output_hash, outcome, correlation_id,
                    previous_hash, hash)
                values (:id, :sequence, :occurredAt, :recordedAt, :agentId, :principalId, :action, :target, :decision,
                    :reason, :delegationId, :inputHash, :outputHash, :outcome, :correlationId, :previousHash, :hash)
                """, new MapSqlParameterSource()
                .addValue("id", entry.id())
                .addValue("sequence", entry.sequence())
                .addValue("occurredAt", Timestamp.from(entry.occurredAt()))
                .addValue("recordedAt", Timestamp.from(entry.recordedAt()))
                .addValue("agentId", entry.agentId())
                .addValue("principalId", entry.principalId())
                .addValue("action", entry.action())
                .addValue("target", entry.target())
                .addValue("decision", entry.decision())
                .addValue("reason", entry.reason())
                .addValue("delegationId", entry.delegationId())
                .addValue("inputHash", entry.inputHash())
                .addValue("outputHash", entry.outputHash())
                .addValue("outcome", entry.outcome())
                .addValue("correlationId", entry.correlationId())
                .addValue("previousHash", entry.previousHash())
                .addValue("hash", entry.hash()));
        entry.attributes().forEach((name, value) -> jdbc.update("""
                insert into ledger.evidence_attribute (evidence_id, name, value) values (:id, :name, :value)
                """, Map.of("id", entry.id(), "name", name, "value", value)));
        jdbc.update("update ledger.ledger_head set sequence = :sequence, hash = :hash where id = 1",
                Map.of("sequence", entry.sequence(), "hash", entry.hash()));
    }

    @Override
    public Optional<EvidenceEntry> findById(UUID id) {
        return single(SELECT + " where id = :id", new MapSqlParameterSource("id", id));
    }

    @Override
    public Optional<EvidenceEntry> findBySequence(long sequence) {
        return single(SELECT + " where sequence = :sequence", new MapSqlParameterSource("sequence", sequence));
    }

    @Override
    public List<EvidenceEntry> find(EvidenceQuery query) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where sequence > :after");
        MapSqlParameterSource params = new MapSqlParameterSource("after", query.afterSequence())
                .addValue("limit", query.limit());
        if (query.agentId() != null) {
            sql.append(" and agent_id = :agentId");
            params.addValue("agentId", query.agentId());
        }
        if (query.principalId() != null) {
            sql.append(" and principal_id = :principalId");
            params.addValue("principalId", query.principalId());
        }
        sql.append(" order by sequence limit :limit");
        return withAttributes(jdbc.query(sql.toString(), params, JdbcEvidenceRepository::row));
    }

    @Override
    public List<EvidenceEntry> range(long afterSequence, int limit) {
        return withAttributes(jdbc.query(SELECT + " where sequence > :after order by sequence limit :limit",
                new MapSqlParameterSource("after", afterSequence).addValue("limit", limit),
                JdbcEvidenceRepository::row));
    }

    private Optional<EvidenceEntry> single(String sql, MapSqlParameterSource params) {
        return withAttributes(jdbc.query(sql, params, JdbcEvidenceRepository::row)).stream().findFirst();
    }

    private List<EvidenceEntry> withAttributes(List<EvidenceEntry> entries) {
        if (entries.isEmpty()) {
            return entries;
        }
        Map<UUID, TreeMap<String, String>> attributes = new HashMap<>();
        jdbc.query("select evidence_id, name, value from ledger.evidence_attribute where evidence_id in (:ids)",
                Map.of("ids", entries.stream().map(EvidenceEntry::id).toList()),
                rs -> {
                    attributes.computeIfAbsent(rs.getObject("evidence_id", UUID.class), id -> new TreeMap<>())
                            .put(rs.getString("name"), rs.getString("value"));
                });
        List<EvidenceEntry> result = new ArrayList<>(entries.size());
        for (EvidenceEntry e : entries) {
            result.add(new EvidenceEntry(e.id(), e.sequence(), e.occurredAt(), e.recordedAt(), e.agentId(),
                    e.principalId(), e.action(), e.target(), e.decision(), e.reason(), e.delegationId(),
                    e.inputHash(), e.outputHash(), e.outcome(), e.correlationId(),
                    attributes.getOrDefault(e.id(), new TreeMap<>()), e.previousHash(), e.hash()));
        }
        return result;
    }

    private static EvidenceEntry row(ResultSet rs, int row) throws SQLException {
        return new EvidenceEntry(
                rs.getObject("id", UUID.class),
                rs.getLong("sequence"),
                instant(rs, "occurred_at"),
                instant(rs, "recorded_at"),
                rs.getString("agent_id"),
                rs.getString("principal_id"),
                rs.getString("action"),
                rs.getString("target"),
                rs.getString("decision"),
                rs.getString("reason"),
                rs.getString("delegation_id"),
                rs.getString("input_hash"),
                rs.getString("output_hash"),
                rs.getString("outcome"),
                rs.getString("correlation_id"),
                new TreeMap<>(),
                rs.getString("previous_hash"),
                rs.getString("hash"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}
