package com.nexusphere.ledger.evidence.infrastructure.persistence;

import com.nexusphere.ledger.chain.CanonicalJson;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.evidence.domain.model.EvidenceQuery;
import com.nexusphere.ledger.evidence.domain.model.LedgerHead;
import com.nexusphere.ledger.evidence.domain.model.Selection;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

@Repository
class JdbcEvidenceRepository implements EvidenceRepository {

    private static final String SELECT = """
            select r.id, r.sequence, r.occurred_at, r.recorded_at, r.agent_id, r.action, r.decision, r.delegation_id,
                   r.input_hash, r.output_hash, r.outcome, r.commitments, r.previous_hash, r.hash,
                   p.nonce, p.ciphertext, k.data_key
            from ledger.evidence_record r
            left join ledger.evidence_personal p on p.evidence_id = r.id
            left join ledger.principal_key k on k.principal_ref = p.principal_ref
            """;
    private static final String PRINCIPAL = "(select principal_ref from ledger.principal_key"
            + " where principal_id = :principalId)";

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    JdbcEvidenceRepository(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
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
        PrincipalKey key = principalKey(entry.principalId());
        jdbc.update("""
                insert into ledger.evidence_record (id, sequence, occurred_at, recorded_at, agent_id, principal_ref,
                    action, decision, delegation_id, input_hash, output_hash, outcome, commitments, previous_hash,
                    hash)
                values (:id, :sequence, :occurredAt, :recordedAt, :agentId, :principalRef, :action, :decision,
                    :delegationId, :inputHash, :outputHash, :outcome, :commitments, :previousHash, :hash)
                """, new MapSqlParameterSource()
                .addValue("id", entry.id())
                .addValue("sequence", entry.sequence())
                .addValue("occurredAt", Timestamp.from(entry.occurredAt()))
                .addValue("recordedAt", Timestamp.from(entry.recordedAt()))
                .addValue("agentId", entry.agentId())
                .addValue("principalRef", key.ref())
                .addValue("action", entry.action())
                .addValue("decision", entry.decision())
                .addValue("delegationId", entry.delegationId())
                .addValue("inputHash", entry.inputHash())
                .addValue("outputHash", entry.outputHash())
                .addValue("outcome", entry.outcome())
                .addValue("commitments", CanonicalJson.write(entry.commitments()))
                .addValue("previousHash", entry.previousHash())
                .addValue("hash", entry.hash()));
        PersonalCipher.Sealed sealed = PersonalCipher.seal(key.dataKey(), entry.id(), personal(entry));
        jdbc.update("""
                insert into ledger.evidence_personal (evidence_id, principal_ref, nonce, ciphertext)
                values (:id, :principalRef, :nonce, :ciphertext)
                """, new MapSqlParameterSource("id", entry.id()).addValue("principalRef", key.ref())
                .addValue("nonce", sealed.nonce()).addValue("ciphertext", sealed.ciphertext()));
        jdbc.update("update ledger.ledger_head set sequence = :sequence, hash = :hash where id = 1",
                Map.of("sequence", entry.sequence(), "hash", entry.hash()));
    }

    @Override
    public Optional<EvidenceEntry> findById(UUID id) {
        return single(SELECT + " where r.id = :id", new MapSqlParameterSource("id", id));
    }

    @Override
    public Optional<EvidenceEntry> findBySequence(long sequence) {
        return single(SELECT + " where r.sequence = :sequence", new MapSqlParameterSource("sequence", sequence));
    }

    @Override
    public Optional<EvidenceEntry> latestByAction(String action) {
        return single(SELECT + " where r.action = :action order by r.sequence desc limit 1",
                new MapSqlParameterSource("action", action));
    }

    @Override
    public List<EvidenceEntry> find(EvidenceQuery query) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where r.sequence > :after");
        MapSqlParameterSource params = new MapSqlParameterSource("after", query.afterSequence())
                .addValue("limit", query.limit());
        if (query.agentId() != null) {
            sql.append(" and r.agent_id = :agentId");
            params.addValue("agentId", query.agentId());
        }
        if (query.principalId() != null) {
            sql.append(" and r.principal_ref = ").append(PRINCIPAL);
            params.addValue("principalId", query.principalId());
        }
        sql.append(" order by r.sequence limit :limit");
        return jdbc.query(sql.toString(), params, this::row);
    }

    @Override
    public List<EvidenceEntry> range(long afterSequence, int limit) {
        return jdbc.query(SELECT + " where r.sequence > :after order by r.sequence limit :limit",
                new MapSqlParameterSource("after", afterSequence).addValue("limit", limit), this::row);
    }

    @Override
    public Selection select(String agentId, String principalId, long fromSequence, long toSequence) {
        StringBuilder sql = new StringBuilder("""
                select count(*) as selected, coalesce(min(sequence), 0) as first, coalesce(max(sequence), 0) as last
                from ledger.evidence_record where sequence between :from and :to
                """);
        MapSqlParameterSource params = new MapSqlParameterSource("from", fromSequence).addValue("to", toSequence);
        if (agentId != null) {
            sql.append(" and agent_id = :agentId");
            params.addValue("agentId", agentId);
        }
        if (principalId != null) {
            sql.append(" and principal_ref = ").append(PRINCIPAL);
            params.addValue("principalId", principalId);
        }
        return jdbc.queryForObject(sql.toString(), params, (rs, row) -> new Selection(rs.getLong("selected"),
                rs.getLong("first"), rs.getLong("last")));
    }

    private record PrincipalKey(UUID ref, byte[] dataKey) {
    }

    private PrincipalKey principalKey(String principalId) {
        Map<String, Object> params = Map.of("principalId", principalId);
        String select = "select principal_ref, data_key from ledger.principal_key where principal_id = :principalId";
        List<PrincipalKey> found = jdbc.query(select, params,
                (rs, row) -> new PrincipalKey(rs.getObject("principal_ref", UUID.class), rs.getBytes("data_key")));
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        jdbc.update("""
                insert into ledger.principal_key (principal_ref, principal_id, data_key, created_at)
                values (:ref, :principalId, :key, now()) on conflict (principal_id) do nothing
                """, new MapSqlParameterSource("ref", UUID.randomUUID()).addValue("principalId", principalId)
                .addValue("key", PersonalCipher.newKey()));
        return jdbc.queryForObject(select, params,
                (rs, row) -> new PrincipalKey(rs.getObject("principal_ref", UUID.class), rs.getBytes("data_key")));
    }

    private byte[] personal(EvidenceEntry entry) {
        Map<String, Object> personal = new LinkedHashMap<>();
        personal.put("principalId", entry.principalId());
        personal.put("target", entry.target());
        personal.put("reason", entry.reason());
        personal.put("correlationId", entry.correlationId());
        personal.put("attributes", entry.attributes());
        personal.put("salts", entry.salts());
        return json.writeValueAsBytes(personal);
    }

    private Optional<EvidenceEntry> single(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, this::row).stream().findFirst();
    }

    private EvidenceEntry row(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        SortedMap<String, String> commitments = strings(json.readTree(rs.getString("commitments")));
        byte[] key = rs.getBytes("data_key");
        byte[] ciphertext = rs.getBytes("ciphertext");
        if (key == null || ciphertext == null) {
            return new EvidenceEntry(id, rs.getLong("sequence"), instant(rs, "occurred_at"),
                    instant(rs, "recorded_at"), rs.getString("agent_id"), null, rs.getString("action"), null,
                    rs.getString("decision"), null, rs.getString("delegation_id"), rs.getString("input_hash"),
                    rs.getString("output_hash"), rs.getString("outcome"), null, null, null, commitments,
                    rs.getString("previous_hash"), rs.getString("hash"));
        }
        JsonNode personal = json.readTree(PersonalCipher.open(key, id, rs.getBytes("nonce"), ciphertext));
        return new EvidenceEntry(id, rs.getLong("sequence"), instant(rs, "occurred_at"), instant(rs, "recorded_at"),
                rs.getString("agent_id"), text(personal, "principalId"), rs.getString("action"),
                text(personal, "target"), rs.getString("decision"), text(personal, "reason"),
                rs.getString("delegation_id"), rs.getString("input_hash"), rs.getString("output_hash"),
                rs.getString("outcome"), text(personal, "correlationId"), strings(personal.path("attributes")),
                strings(personal.path("salts")), null, rs.getString("previous_hash"), rs.getString("hash"));
    }

    private static SortedMap<String, String> strings(JsonNode node) {
        SortedMap<String, String> map = new TreeMap<>();
        node.propertyNames().forEach(name -> map.put(name, node.path(name).isNull() ? null
                : node.path(name).asString()));
        return map;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}
