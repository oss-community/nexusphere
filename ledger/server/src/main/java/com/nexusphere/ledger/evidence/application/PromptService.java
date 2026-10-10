package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class PromptService {

    public static final int MAX_BYTES = 1_048_576;

    public record Prompt(String hash, String text, Instant createdAt, String createdBy) {
    }

    public record Stored(String hash, boolean created) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    PromptService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Stored store(String text, String createdBy) {
        if (text == null || text.isEmpty()) {
            throw LedgerException.invalid("The prompt has invalid fields.", Map.of("text", "is required"));
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw LedgerException.invalid("The prompt has invalid fields.",
                    Map.of("text", "must be at most " + MAX_BYTES + " bytes"));
        }
        String hash = Hashes.sha256(bytes);
        int inserted = jdbc.update("""
                insert into ledger.prompt (hash, text, created_at, created_by)
                values (:hash, :text, :createdAt, :createdBy)
                on conflict (hash) do nothing
                """, new MapSqlParameterSource()
                .addValue("hash", hash)
                .addValue("text", text)
                .addValue("createdAt", Timestamp.from(clock.instant()))
                .addValue("createdBy", createdBy));
        return new Stored(hash, inserted == 1);
    }

    @Transactional(readOnly = true)
    public Prompt get(String hash) {
        List<Prompt> found = jdbc.query("""
                select hash, text, created_at, created_by from ledger.prompt where hash = :hash
                """, Map.of("hash", hash), (rs, n) -> new Prompt(rs.getString("hash"), rs.getString("text"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("created_by")));
        if (found.isEmpty()) {
            throw LedgerException.notFound("Prompt " + hash);
        }
        return found.getFirst();
    }

    @Transactional(readOnly = true)
    public Map<String, String> texts(Collection<String> hashes) {
        Map<String, String> texts = new TreeMap<>();
        if (hashes.isEmpty()) {
            return texts;
        }
        jdbc.query("select hash, text from ledger.prompt where hash in (:hashes)", Map.of("hashes", hashes),
                rs -> {
                    texts.put(rs.getString("hash"), rs.getString("text"));
                });
        return texts;
    }
}
