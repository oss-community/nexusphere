package com.nexusphere.ledger.authorization.infrastructure.persistence;

import com.nexusphere.ledger.authorization.domain.model.DecisionRecord;
import com.nexusphere.ledger.authorization.domain.model.ReasonCode;
import com.nexusphere.ledger.authorization.domain.repository.DecisionRepository;
import com.nexusphere.ledger.evidence.domain.model.Decision;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcDecisionRepository implements DecisionRepository {

    private static final String SELECT = """
            select id, agent_id, principal_id, action, target, decision, reason_code, grant_id, decided_at, outcome,
                   outcome_evidence_id
            from ledger.decision
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcDecisionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(DecisionRecord d) {
        jdbc.update("""
                insert into ledger.decision (id, agent_id, principal_id, action, target, decision, reason_code,
                    grant_id, decided_at)
                values (:id, :agentId, :principalId, :action, :target, :decision, :reasonCode, :grantId, :decidedAt)
                """, new MapSqlParameterSource()
                .addValue("id", d.id())
                .addValue("agentId", d.agentId())
                .addValue("principalId", d.principalId())
                .addValue("action", d.action())
                .addValue("target", d.target())
                .addValue("decision", d.decision().name())
                .addValue("reasonCode", d.reasonCode().name())
                .addValue("grantId", d.grantId())
                .addValue("decidedAt", Timestamp.from(d.decidedAt())));
    }

    @Override
    public Optional<DecisionRecord> find(UUID id) {
        return jdbc.query(SELECT + " where id = :id", Map.of("id", id), JdbcDecisionRepository::row)
                .stream().findFirst();
    }

    @Override
    public Optional<DecisionRecord> lock(UUID id) {
        return jdbc.query(SELECT + " where id = :id for update", Map.of("id", id), JdbcDecisionRepository::row)
                .stream().findFirst();
    }

    @Override
    public void recordOutcome(UUID id, Outcome outcome, UUID outcomeEvidenceId) {
        jdbc.update("update ledger.decision set outcome = :outcome, outcome_evidence_id = :evidenceId where id = :id",
                Map.of("outcome", outcome.name(), "evidenceId", outcomeEvidenceId, "id", id));
    }

    private static DecisionRecord row(ResultSet rs, int row) throws SQLException {
        String outcome = rs.getString("outcome");
        return new DecisionRecord(rs.getObject("id", UUID.class), rs.getString("agent_id"),
                rs.getString("principal_id"), rs.getString("action"), rs.getString("target"),
                Decision.valueOf(rs.getString("decision")), ReasonCode.valueOf(rs.getString("reason_code")),
                rs.getObject("grant_id", UUID.class), rs.getTimestamp("decided_at").toInstant(),
                outcome == null ? null : Outcome.valueOf(outcome), rs.getObject("outcome_evidence_id", UUID.class));
    }
}
