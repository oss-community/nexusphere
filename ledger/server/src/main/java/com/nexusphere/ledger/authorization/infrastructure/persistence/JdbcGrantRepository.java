package com.nexusphere.ledger.authorization.infrastructure.persistence;

import com.nexusphere.ledger.authorization.domain.model.Consent;
import com.nexusphere.ledger.authorization.domain.model.Grant;
import com.nexusphere.ledger.authorization.domain.model.GrantQuery;
import com.nexusphere.ledger.authorization.domain.model.GrantState;
import com.nexusphere.ledger.authorization.domain.repository.GrantRepository;
import com.nexusphere.ledger.chain.GrantTerms;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcGrantRepository implements GrantRepository {

    private static final String SELECT = """
            select seq, id, principal_id, agent_id, actions, targets, not_before, expires_at, max_uses, uses, status,
                   reason, created_at, revoked_at, revoke_reason, consent, consented_at
            from ledger.grant_record
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcGrantRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(GrantTerms terms, String reason, GrantState state, Consent consent) {
        jdbc.update("""
                insert into ledger.grant_record (id, principal_id, agent_id, actions, targets, not_before, expires_at,
                    max_uses, status, terms_hash, reason, created_at, consent, consented_at)
                values (:id, :principalId, :agentId, :actions, :targets, :notBefore, :expiresAt, :maxUses, :status,
                    :termsHash, :reason, :createdAt, :consent, :consentedAt)
                """, new MapSqlParameterSource()
                .addValue("id", terms.id())
                .addValue("principalId", terms.principalId())
                .addValue("agentId", terms.agentId())
                .addValue("actions", terms.actions().toArray(String[]::new))
                .addValue("targets", terms.targets().toArray(String[]::new))
                .addValue("notBefore", terms.notBefore() == null ? null : Timestamp.from(terms.notBefore()))
                .addValue("expiresAt", Timestamp.from(terms.expiresAt()))
                .addValue("maxUses", terms.maxUses())
                .addValue("termsHash", terms.hash())
                .addValue("reason", reason)
                .addValue("createdAt", Timestamp.from(terms.createdAt()))
                .addValue("status", state.name())
                .addValue("consent", consent == null ? null : consent.name())
                .addValue("consentedAt", consent == null ? null : Timestamp.from(terms.createdAt())));
    }

    @Override
    public Optional<Grant> find(UUID id) {
        return jdbc.query(SELECT + " where id = :id", Map.of("id", id), JdbcGrantRepository::row)
                .stream().findFirst();
    }

    @Override
    public Optional<Grant> lock(UUID id) {
        return jdbc.query(SELECT + " where id = :id for update", Map.of("id", id), JdbcGrantRepository::row)
                .stream().findFirst();
    }

    @Override
    public List<Grant> lockActive(String agentId, String principalId) {
        return jdbc.query(SELECT + """
                 where agent_id = :agentId and principal_id = :principalId and status = 'ACTIVE'
                 order by expires_at, seq for update
                """, Map.of("agentId", agentId, "principalId", principalId), JdbcGrantRepository::row);
    }

    @Override
    public List<Grant> find(GrantQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("after", query.afterSeq())
                .addValue("limit", query.limit());
        StringBuilder sql = new StringBuilder(SELECT).append(" where seq > :after");
        if (query.agentId() != null) {
            sql.append(" and agent_id = :agentId");
            params.addValue("agentId", query.agentId());
        }
        if (query.state() != null) {
            sql.append(" and status = :state");
            params.addValue("state", query.state().name());
        }
        if (query.principalId() != null) {
            sql.append(" and principal_id = :principalId");
            params.addValue("principalId", query.principalId());
        }
        sql.append(" order by seq limit :limit");
        return jdbc.query(sql.toString(), params, JdbcGrantRepository::row);
    }

    @Override
    public void use(UUID id) {
        jdbc.update("update ledger.grant_record set uses = uses + 1 where id = :id", Map.of("id", id));
    }

    @Override
    public void release(UUID id) {
        jdbc.update("update ledger.grant_record set uses = greatest(uses - 1, 0) where id = :id", Map.of("id", id));
    }

    @Override
    public void revoke(UUID id, Instant revokedAt, String reason) {
        jdbc.update("""
                update ledger.grant_record set status = 'REVOKED', revoked_at = :revokedAt, revoke_reason = :reason
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("revokedAt", Timestamp.from(revokedAt))
                .addValue("reason", reason));
    }

    @Override
    public void approve(UUID id, Instant approvedAt) {
        jdbc.update("""
                update ledger.grant_record set status = 'ACTIVE', consent = 'PRINCIPAL', consented_at = :approvedAt
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("approvedAt", Timestamp.from(approvedAt)));
    }

    @Override
    public void deny(UUID id, Instant deniedAt, String reason) {
        jdbc.update("""
                update ledger.grant_record set status = 'DENIED', revoked_at = :deniedAt, revoke_reason = :reason
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("deniedAt", Timestamp.from(deniedAt))
                .addValue("reason", reason));
    }

    private static Grant row(ResultSet rs, int row) throws SQLException {
        long maxUses = rs.getLong("max_uses");
        Long max = rs.wasNull() ? null : maxUses;
        Timestamp notBefore = rs.getTimestamp("not_before");
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        Timestamp consentedAt = rs.getTimestamp("consented_at");
        String consent = rs.getString("consent");
        GrantTerms terms = new GrantTerms(rs.getObject("id", UUID.class), rs.getString("principal_id"),
                rs.getString("agent_id"), strings(rs.getArray("actions")), strings(rs.getArray("targets")),
                notBefore == null ? null : notBefore.toInstant(), rs.getTimestamp("expires_at").toInstant(), max,
                rs.getTimestamp("created_at").toInstant());
        return new Grant(rs.getLong("seq"), terms, rs.getLong("uses"), GrantState.valueOf(rs.getString("status")),
                rs.getString("reason"), revokedAt == null ? null : revokedAt.toInstant(),
                rs.getString("revoke_reason"), consent == null ? null : Consent.valueOf(consent),
                consentedAt == null ? null : consentedAt.toInstant());
    }

    private static List<String> strings(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }
}
