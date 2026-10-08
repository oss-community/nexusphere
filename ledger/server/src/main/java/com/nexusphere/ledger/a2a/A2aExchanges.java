package com.nexusphere.ledger.a2a;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class A2aExchanges {

    private final NamedParameterJdbcTemplate jdbc;

    A2aExchanges(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean reserve(A2aExchange e) {
        try {
            jdbc.update("""
                    insert into ledger.a2a_exchange (id, direction, peer, request_id, agent_id, principal_id, method,
                        mandate_id, mandate_token, request_token, request_hash, created_at)
                    values (:id, :direction, :peer, :requestId, :agentId, :principalId, :method, :mandateId,
                        :mandateToken, :requestToken, :requestHash, :createdAt)
                    """, new MapSqlParameterSource()
                    .addValue("id", e.id())
                    .addValue("direction", e.direction())
                    .addValue("peer", e.peer())
                    .addValue("requestId", e.requestId())
                    .addValue("agentId", e.agentId())
                    .addValue("principalId", e.principalId())
                    .addValue("method", e.method())
                    .addValue("mandateId", e.mandateId())
                    .addValue("mandateToken", e.mandateToken())
                    .addValue("requestToken", e.requestToken())
                    .addValue("requestHash", e.requestHash())
                    .addValue("createdAt", Timestamp.from(e.createdAt())));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Transactional
    public boolean claimUse(UUID id, String peer, UUID mandateId, long maxUses) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(:key))",
                Map.of("key", "ledger.a2a_exchange." + peer + "." + mandateId));
        Long used = jdbc.queryForObject("""
                select count(*) from ledger.a2a_exchange
                where direction = 'INBOUND' and peer = :peer and mandate_id = :mandateId and use_counted
                """, Map.of("peer", peer, "mandateId", mandateId), Long.class);
        if (used != null && used >= maxUses) {
            return false;
        }
        jdbc.update("update ledger.a2a_exchange set use_counted = true where id = :id", Map.of("id", id));
        return true;
    }

    void releaseUse(UUID id) {
        jdbc.update("update ledger.a2a_exchange set use_counted = false where id = :id", Map.of("id", id));
    }

    void complete(UUID id, String responseHash, Integer status, String outcome, Long evidenceSequence, String receipt,
                  String receiptStatus) {
        jdbc.update("""
                update ledger.a2a_exchange
                set response_hash = :responseHash, status = :status, outcome = :outcome,
                    evidence_sequence = :evidenceSequence, receipt = :receipt, receipt_status = :receiptStatus
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("responseHash", responseHash)
                .addValue("status", status)
                .addValue("outcome", outcome)
                .addValue("evidenceSequence", evidenceSequence)
                .addValue("receipt", receipt)
                .addValue("receiptStatus", receiptStatus));
    }

    Optional<A2aExchange> find(UUID id) {
        return jdbc.query("select * from ledger.a2a_exchange where id = :id", Map.of("id", id), A2aExchanges::row)
                .stream().findFirst();
    }

    private static A2aExchange row(ResultSet rs, int row) throws SQLException {
        return new A2aExchange(rs.getObject("id", UUID.class), rs.getString("direction"), rs.getString("peer"),
                rs.getObject("request_id", UUID.class), rs.getString("agent_id"), rs.getString("principal_id"),
                rs.getString("method"), rs.getObject("mandate_id", UUID.class), rs.getString("mandate_token"),
                rs.getString("request_token"), rs.getString("request_hash"), rs.getString("response_hash"),
                rs.getObject("status", Integer.class), rs.getString("outcome"),
                rs.getObject("evidence_sequence", Long.class), rs.getString("receipt"), rs.getString("receipt_status"),
                rs.getTimestamp("created_at").toInstant());
    }
}
