package com.nexusphere.ledger.agent.infrastructure.persistence;

import com.nexusphere.ledger.agent.domain.model.Agent;
import com.nexusphere.ledger.agent.domain.model.AgentStatus;
import com.nexusphere.ledger.agent.domain.repository.AgentRepository;
import com.nexusphere.ledger.server.security.AgentCredentials;
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
class JdbcAgentRepository implements AgentRepository, AgentCredentials {

    private static final String SELECT =
            "select agent_id, name, owner_id, status, key_prefix, created_at from ledger.agent";

    private final NamedParameterJdbcTemplate jdbc;

    JdbcAgentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(Agent agent, String keyHash) {
        return jdbc.update("""
                insert into ledger.agent (agent_id, name, owner_id, status, key_hash, key_prefix, created_at)
                values (:agentId, :name, :ownerId, :status, :keyHash, :keyPrefix, :createdAt)
                on conflict (agent_id) do nothing
                """, new MapSqlParameterSource()
                .addValue("agentId", agent.agentId())
                .addValue("name", agent.name())
                .addValue("ownerId", agent.ownerId())
                .addValue("status", agent.status().name())
                .addValue("keyHash", keyHash)
                .addValue("keyPrefix", agent.keyPrefix())
                .addValue("createdAt", Timestamp.from(agent.createdAt()))) == 1;
    }

    @Override
    public Optional<Agent> find(String agentId) {
        return jdbc.query(SELECT + " where agent_id = :agentId", Map.of("agentId", agentId),
                JdbcAgentRepository::row).stream().findFirst();
    }

    @Override
    public Optional<Agent> lock(String agentId) {
        return jdbc.query(SELECT + " where agent_id = :agentId for update", Map.of("agentId", agentId),
                JdbcAgentRepository::row).stream().findFirst();
    }

    @Override
    public List<Agent> list(String afterAgentId, int limit) {
        return jdbc.query(SELECT + " where agent_id > :after order by agent_id limit :limit",
                Map.of("after", afterAgentId, "limit", limit), JdbcAgentRepository::row);
    }

    @Override
    public void updateStatus(String agentId, AgentStatus status) {
        jdbc.update("update ledger.agent set status = :status where agent_id = :agentId",
                Map.of("status", status.name(), "agentId", agentId));
    }

    @Override
    public void updateKey(String agentId, String keyHash, String keyPrefix) {
        jdbc.update("update ledger.agent set key_hash = :keyHash, key_prefix = :keyPrefix where agent_id = :agentId",
                Map.of("keyHash", keyHash, "keyPrefix", keyPrefix, "agentId", agentId));
    }

    @Override
    public Optional<String> activeAgentByKeyHash(String keyHash) {
        return jdbc.queryForList("select agent_id from ledger.agent where key_hash = :keyHash and status = 'ACTIVE'",
                Map.of("keyHash", keyHash), String.class).stream().findFirst();
    }

    private static Agent row(ResultSet rs, int row) throws SQLException {
        return new Agent(rs.getString("agent_id"), rs.getString("name"), rs.getString("owner_id"),
                AgentStatus.valueOf(rs.getString("status")), rs.getString("key_prefix"),
                rs.getTimestamp("created_at").toInstant());
    }
}
