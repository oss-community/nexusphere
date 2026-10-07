package com.nexusphere.ledger.authorization.infrastructure.persistence;

import com.nexusphere.ledger.authorization.domain.model.Mandate;
import com.nexusphere.ledger.authorization.domain.repository.MandateRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcMandateRepository implements MandateRepository {

    private static final String SELECT = """
            select id, status_index, grant_id, agent_id, principal_id, audience, issued_at, expires_at, token,
                   revoked_at, revoke_reason
            from ledger.mandate
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcMandateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long reserve(UUID id, UUID grantId, String agentId, String principalId, String audience, Instant issuedAt,
                        Instant expiresAt) {
        return jdbc.queryForObject("""
                insert into ledger.mandate (id, grant_id, agent_id, principal_id, audience, issued_at, expires_at, token)
                values (:id, :grantId, :agentId, :principalId, :audience, :issuedAt, :expiresAt, '')
                returning status_index
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("grantId", grantId)
                .addValue("agentId", agentId)
                .addValue("principalId", principalId)
                .addValue("audience", audience)
                .addValue("issuedAt", Timestamp.from(issuedAt))
                .addValue("expiresAt", Timestamp.from(expiresAt)), Long.class);
    }

    @Override
    public void attachToken(UUID id, String token) {
        jdbc.update("update ledger.mandate set token = :token where id = :id", Map.of("token", token, "id", id));
    }

    @Override
    public Optional<Mandate> find(UUID id) {
        return jdbc.query(SELECT + " where id = :id", Map.of("id", id), JdbcMandateRepository::row)
                .stream().findFirst();
    }

    @Override
    public Optional<Mandate> lock(UUID id) {
        return jdbc.query(SELECT + " where id = :id for update", Map.of("id", id), JdbcMandateRepository::row)
                .stream().findFirst();
    }

    @Override
    public List<Mandate> lockActiveForGrant(UUID grantId) {
        return jdbc.query(SELECT + " where grant_id = :grantId and revoked_at is null order by status_index for update",
                Map.of("grantId", grantId), JdbcMandateRepository::row);
    }

    @Override
    public List<Mandate> findByGrant(UUID grantId) {
        return jdbc.query(SELECT + " where grant_id = :grantId order by status_index", Map.of("grantId", grantId),
                JdbcMandateRepository::row);
    }

    @Override
    public void revoke(UUID id, Instant revokedAt, String reason) {
        jdbc.update("update ledger.mandate set revoked_at = :revokedAt, revoke_reason = :reason where id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("revokedAt", Timestamp.from(revokedAt))
                        .addValue("reason", reason));
    }

    @Override
    public BitSet revokedIndexes() {
        BitSet bits = new BitSet();
        jdbc.query("select status_index from ledger.mandate where revoked_at is not null", Map.of(),
                rs -> {
                    bits.set((int) rs.getLong("status_index"));
                });
        return bits;
    }

    private static Mandate row(ResultSet rs, int row) throws SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new Mandate(rs.getObject("id", UUID.class), rs.getLong("status_index"),
                rs.getObject("grant_id", UUID.class), rs.getString("agent_id"), rs.getString("principal_id"),
                rs.getString("audience"), rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("token"),
                revokedAt == null ? null : revokedAt.toInstant(), rs.getString("revoke_reason"));
    }
}
