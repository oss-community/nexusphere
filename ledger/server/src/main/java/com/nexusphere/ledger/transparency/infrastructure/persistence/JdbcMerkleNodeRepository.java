package com.nexusphere.ledger.transparency.infrastructure.persistence;

import com.nexusphere.ledger.transparency.domain.repository.MerkleNodeRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
class JdbcMerkleNodeRepository implements MerkleNodeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcMerkleNodeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void put(int level, long index, byte[] hash) {
        jdbc.update("insert into ledger.merkle_node (level, idx, hash) values (:level, :index, :hash)",
                new MapSqlParameterSource().addValue("level", level).addValue("index", index).addValue("hash", hash));
    }

    @Override
    public Optional<byte[]> find(int level, long index) {
        return jdbc.query("select hash from ledger.merkle_node where level = :level and idx = :index",
                new MapSqlParameterSource().addValue("level", level).addValue("index", index),
                (rs, row) -> rs.getBytes("hash")).stream().findFirst();
    }
}
