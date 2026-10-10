package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LegalHoldService {

    public static final String PLACE = "legal-hold/place";
    public static final String RELEASE = "legal-hold/release";
    private static final String SELECT = """
            select h.id, h.principal_ref, k.principal_id, h.reason, h.placed_at, h.released_at, h.release_reason
            from ledger.legal_hold h join ledger.principal_key k on k.principal_ref = h.principal_ref
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final EvidenceRepository repository;
    private final EvidenceService evidence;
    private final Clock clock;

    LegalHoldService(NamedParameterJdbcTemplate jdbc, EvidenceRepository repository, EvidenceService evidence,
                     Clock clock) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.evidence = evidence;
        this.clock = clock;
    }

    public record LegalHold(UUID id, UUID principalRef, String principalId, String reason, Instant placedAt,
                            Instant releasedAt, String releaseReason) {
    }

    @Transactional
    public LegalHold place(String principalId, String reason) {
        check(principalId, reason);
        repository.lockHead();
        List<UUID> refs = jdbc.queryForList("""
                select principal_ref from ledger.principal_key where principal_id = :principalId
                """, Map.of("principalId", principalId), UUID.class);
        if (refs.isEmpty()) {
            throw LedgerException.notFound("Evidence about the principal");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                insert into ledger.legal_hold (id, principal_ref, reason, placed_at)
                values (:id, :ref, :reason, :now)
                """, new MapSqlParameterSource("id", id).addValue("ref", refs.getFirst()).addValue("reason", reason)
                .addValue("now", Timestamp.from(now)));
        record(PLACE, refs.getFirst(), reason, id, now);
        return get(id);
    }

    @Transactional
    public LegalHold release(UUID id, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw LedgerException.invalid("The release has invalid fields.",
                    Map.of("reason", "is required and at most 500 characters"));
        }
        repository.lockHead();
        LegalHold hold = get(id);
        if (hold.releasedAt() != null) {
            throw LedgerException.conflict("HOLD_RELEASED", "The legal hold was already released.");
        }
        Instant now = clock.instant();
        jdbc.update("""
                update ledger.legal_hold set released_at = :now, release_reason = :reason where id = :id
                """, Map.of("id", id, "now", Timestamp.from(now), "reason", reason));
        record(RELEASE, hold.principalRef(), reason, id, now);
        return get(id);
    }

    public LegalHold get(UUID id) {
        List<LegalHold> found = jdbc.query(SELECT + " where h.id = :id", Map.of("id", id), LegalHoldService::map);
        if (found.isEmpty()) {
            throw LedgerException.notFound("Legal hold " + id);
        }
        return found.getFirst();
    }

    public List<LegalHold> list(boolean active) {
        return jdbc.query(SELECT + (active ? " where h.released_at is null" : "") + " order by h.placed_at",
                Map.of(), LegalHoldService::map);
    }

    private void record(String action, UUID ref, String reason, UUID id, Instant now) {
        evidence.record(new EvidenceSubmission(now, "ledger", "operator", action, "principal:" + ref, null, reason,
                null, null, null, Outcome.SUCCEEDED, null, Map.of("legalHold", id.toString())));
    }

    private static void check(String principalId, String reason) {
        if (principalId == null || principalId.isBlank()) {
            throw LedgerException.invalid("The legal hold has invalid fields.", Map.of("principalId", "is required"));
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw LedgerException.invalid("The legal hold has invalid fields.",
                    Map.of("reason", "is required and at most 500 characters"));
        }
    }

    private static LegalHold map(ResultSet rs, int row) throws SQLException {
        Timestamp released = rs.getTimestamp("released_at");
        return new LegalHold(rs.getObject("id", UUID.class), rs.getObject("principal_ref", UUID.class),
                rs.getString("principal_id"), rs.getString("reason"), rs.getTimestamp("placed_at").toInstant(),
                released == null ? null : released.toInstant(), rs.getString("release_reason"));
    }
}
