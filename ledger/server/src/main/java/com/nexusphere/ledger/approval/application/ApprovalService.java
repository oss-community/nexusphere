package com.nexusphere.ledger.approval.application;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.Timestamps;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.policy.application.CedarPolicies;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApprovalService {

    public static final String ID_ATTRIBUTE = "approval.id";
    public static final String EXPIRES_ATTRIBUTE = "approval.expiresAt";
    public static final int MAX_LIMIT = 500;

    public enum Answer {
        APPROVED,
        PENDING,
        DENIED,
        EXPIRED
    }

    public record Gate(Answer answer, Approval approval) {
    }

    public record Request(String agentId, String principalId, String action, String target, String inputHash,
                          String policyHash, List<String> policies, String approver) {
    }

    public record Resolver(String method, String by, String principalId, Map<String, String> attributes) {
    }

    private static final String COLUMNS = """
            id, agent_id, principal_id, action, target, input_hash, policy_hash, policies, approver, status,
            requested_at, expires_at, resolved_at, resolved_by, method, reason, evidence_id, closed_at
            """;
    private static final RowMapper<Approval> ROWS = (rs, n) -> new Approval(rs.getObject("id", UUID.class),
            rs.getString("agent_id"), rs.getString("principal_id"), rs.getString("action"), rs.getString("target"),
            rs.getString("input_hash"), rs.getString("policy_hash"),
            Arrays.asList(rs.getString("policies").split(",")), rs.getString("approver"),
            Approval.Status.valueOf(rs.getString("status")), instant(rs.getTimestamp("requested_at")),
            instant(rs.getTimestamp("expires_at")), instant(rs.getTimestamp("resolved_at")),
            rs.getString("resolved_by"), rs.getString("method"), rs.getString("reason"),
            rs.getObject("evidence_id", UUID.class), instant(rs.getTimestamp("closed_at")));

    private final NamedParameterJdbcTemplate jdbc;
    private final EvidenceService evidence;
    private final Clock clock;
    private final Duration timeout;

    ApprovalService(NamedParameterJdbcTemplate jdbc, EvidenceService evidence, LedgerProperties properties,
                    Clock clock) {
        this.jdbc = jdbc;
        this.evidence = evidence;
        this.clock = clock;
        this.timeout = properties.approval() == null || properties.approval().timeout() == null
                ? Duration.ofMinutes(15) : properties.approval().timeout();
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException("ledger.approval.timeout must be positive");
        }
    }

    public Duration timeout() {
        return timeout;
    }

    @Transactional
    public Gate gate(Request request, Instant now) {
        String fingerprint = fingerprint(request);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:f, 0)) is null",
                Map.of("f", fingerprint), Boolean.class);
        Optional<Approval> open = jdbc.query("select " + COLUMNS + """
                from ledger.approval where fingerprint = :f and closed_at is null
                order by requested_at desc limit 1 for update
                """, Map.of("f", fingerprint), ROWS).stream().findFirst();
        if (open.isEmpty()) {
            return new Gate(Answer.PENDING, create(request, fingerprint, now));
        }
        Approval approval = open.get();
        if (approval.status() == Approval.Status.PENDING && approval.expiresAt().isAfter(now)) {
            return new Gate(Answer.PENDING, approval);
        }
        if (approval.status() == Approval.Status.PENDING) {
            approval = expire(approval, now);
        }
        close(approval.id(), now);
        if (approval.status() == Approval.Status.APPROVED) {
            return new Gate(approval.expiresAt().isAfter(now) ? Answer.APPROVED : Answer.EXPIRED, approval);
        }
        return new Gate(approval.status() == Approval.Status.DENIED ? Answer.DENIED : Answer.EXPIRED, approval);
    }

    @Transactional
    public Approval approve(UUID id, Resolver resolver, String reason) {
        return resolve(id, resolver, reason, Approval.Status.APPROVED);
    }

    @Transactional
    public Approval deny(UUID id, Resolver resolver, String reason) {
        return resolve(id, resolver, reason, Approval.Status.DENIED);
    }

    @Transactional
    public int expireDue() {
        Instant now = clock.instant();
        List<Approval> due = jdbc.query("select " + COLUMNS + """
                from ledger.approval where status = 'PENDING' and expires_at <= :now
                order by expires_at limit 100 for update skip locked
                """, Map.of("now", Timestamp.from(now)), ROWS);
        due.forEach(a -> expire(a, now));
        return due.size();
    }

    @Transactional(readOnly = true)
    public Approval get(UUID id) {
        return find(id).orElseThrow(() -> LedgerException.notFound("Approval " + id));
    }

    @Transactional(readOnly = true)
    public Optional<Approval> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from ledger.approval where id = :id", Map.of("id", id), ROWS)
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<Approval> list(Approval.Status status, String principalId, String agentId, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw LedgerException.invalid("The query has invalid parameters.",
                    Map.of("limit", "must be between 1 and " + MAX_LIMIT));
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", status == null ? null : status.name())
                .addValue("principalId", principalId)
                .addValue("agentId", agentId)
                .addValue("limit", limit);
        return jdbc.query("select " + COLUMNS + """
                from ledger.approval
                where (cast(:status as varchar) is null or status = :status)
                  and (cast(:principalId as varchar) is null or principal_id = :principalId)
                  and (cast(:agentId as varchar) is null or agent_id = :agentId)
                order by requested_at desc limit :limit
                """, params, ROWS);
    }

    public record CibaPoll(Approval approval, String requestId) {
    }

    @Transactional(readOnly = true)
    public List<Approval> unnotified() {
        return jdbc.query("select " + COLUMNS + """
                from ledger.approval where status = 'PENDING' and notified_at is null and expires_at > :now
                order by requested_at limit 100
                """, Map.of("now", Timestamp.from(clock.instant())), ROWS);
    }

    @Transactional
    public void notified(UUID id) {
        jdbc.update("update ledger.approval set notified_at = :now where id = :id",
                Map.of("now", Timestamp.from(clock.instant()), "id", id));
    }

    @Transactional(readOnly = true)
    public List<Approval> cibaUnstarted() {
        return jdbc.query("select " + COLUMNS + """
                from ledger.approval
                where status = 'PENDING' and ciba_request_id is null and ciba_poll_at is null and expires_at > :now
                  and approver in ('principal', 'any')
                order by requested_at limit 100
                """, Map.of("now", Timestamp.from(clock.instant())), ROWS);
    }

    @Transactional(readOnly = true)
    public List<CibaPoll> cibaDue() {
        return jdbc.query("select " + COLUMNS + """
                , ciba_request_id from ledger.approval
                where status = 'PENDING' and ciba_request_id is not null and ciba_poll_at <= :now
                order by ciba_poll_at limit 100
                """, Map.of("now", Timestamp.from(clock.instant())),
                (rs, n) -> new CibaPoll(ROWS.mapRow(rs, n), rs.getString("ciba_request_id")));
    }

    @Transactional
    public void ciba(UUID id, String requestId, Instant pollAt) {
        jdbc.update("update ledger.approval set ciba_request_id = :requestId, ciba_poll_at = :pollAt where id = :id",
                new MapSqlParameterSource().addValue("requestId", requestId)
                        .addValue("pollAt", Timestamp.from(pollAt)).addValue("id", id));
    }

    @Transactional
    public void cibaStopped(UUID id) {
        jdbc.update("update ledger.approval set ciba_request_id = null, ciba_poll_at = expires_at where id = :id",
                Map.of("id", id));
    }

    private Approval create(Request request, String fingerprint, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into ledger.approval (id, fingerprint, agent_id, principal_id, action, target, input_hash,
                    policy_hash, policies, approver, status, requested_at, expires_at)
                values (:id, :fingerprint, :agentId, :principalId, :action, :target, :inputHash, :policyHash,
                    :policies, :approver, 'PENDING', :now, :expiresAt)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("fingerprint", fingerprint)
                .addValue("agentId", request.agentId())
                .addValue("principalId", request.principalId())
                .addValue("action", request.action())
                .addValue("target", request.target())
                .addValue("inputHash", request.inputHash())
                .addValue("policyHash", request.policyHash())
                .addValue("policies", String.join(",", request.policies()))
                .addValue("approver", request.approver())
                .addValue("now", Timestamp.from(now))
                .addValue("expiresAt", Timestamp.from(now.plus(timeout))));
        return get(id);
    }

    private Approval resolve(UUID id, Resolver resolver, String reason, Approval.Status status) {
        Instant now = clock.instant();
        Approval approval = jdbc.query("select " + COLUMNS + " from ledger.approval where id = :id for update",
                Map.of("id", id), ROWS).stream().findFirst()
                .filter(a -> resolver.principalId() == null || a.principalId().equals(resolver.principalId()))
                .orElseThrow(() -> LedgerException.notFound("Approval " + id));
        if (reason != null && reason.length() > 500) {
            throw LedgerException.invalid("The approval has invalid fields.",
                    Map.of("reason", "must be at most 500 characters"));
        }
        boolean operator = resolver.method().equals(CedarPolicies.OPERATOR);
        boolean allowed = approval.approver().equals(CedarPolicies.ANY)
                || approval.approver().equals(operator ? CedarPolicies.OPERATOR : CedarPolicies.PRINCIPAL);
        if (!allowed) {
            throw LedgerException.forbidden("Approval " + id + " must be answered by the " + approval.approver()
                    + ".");
        }
        if (approval.status() == Approval.Status.PENDING && !approval.expiresAt().isAfter(now)) {
            expire(approval, now);
            throw LedgerException.conflict("APPROVAL_EXPIRED", "Approval " + id + " has expired.");
        }
        if (approval.status() != Approval.Status.PENDING) {
            throw LedgerException.conflict("APPROVAL_RESOLVED", "Approval " + id + " is already "
                    + approval.status().name().toLowerCase() + ".");
        }
        Map<String, String> attributes = attributes(approval);
        attributes.put("approval.method", resolver.method());
        attributes.putAll(resolver.attributes());
        String action = status == Approval.Status.APPROVED ? "approval/approve" : "approval/deny";
        UUID evidenceId = record(approval, action, reason, attributes, now);
        Instant expiresAt = status == Approval.Status.APPROVED ? now.plus(timeout) : approval.expiresAt();
        jdbc.update("""
                update ledger.approval set status = :status, resolved_at = :now, resolved_by = :by, method = :method,
                    reason = :reason, evidence_id = :evidenceId, expires_at = :expiresAt
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("status", status.name())
                .addValue("now", Timestamp.from(now))
                .addValue("by", resolver.by())
                .addValue("method", resolver.method())
                .addValue("reason", reason)
                .addValue("evidenceId", evidenceId)
                .addValue("expiresAt", Timestamp.from(expiresAt))
                .addValue("id", id));
        return get(id);
    }

    private Approval expire(Approval approval, Instant now) {
        Map<String, String> attributes = attributes(approval);
        attributes.put("approval.method", "timeout");
        UUID evidenceId = record(approval, "approval/expire", "Nobody answered approval " + approval.id()
                + " before " + Timestamps.format(approval.expiresAt()) + ".", attributes, now);
        jdbc.update("""
                update ledger.approval set status = 'EXPIRED', resolved_at = :now, method = 'timeout',
                    evidence_id = :evidenceId
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("evidenceId", evidenceId)
                .addValue("id", approval.id()));
        return get(approval.id());
    }

    private void close(UUID id, Instant now) {
        jdbc.update("update ledger.approval set closed_at = :now where id = :id",
                Map.of("now", Timestamp.from(now), "id", id));
    }

    private UUID record(Approval approval, String action, String reason, Map<String, String> attributes,
                        Instant now) {
        return evidence.record(new EvidenceSubmission(now, approval.agentId(), approval.principalId(), action,
                approval.target(), null, reason, null, approval.inputHash(), null, Outcome.SUCCEEDED, null,
                attributes)).id();
    }

    private static Map<String, String> attributes(Approval approval) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ID_ATTRIBUTE, approval.id().toString());
        attributes.put("approval.action", approval.action());
        attributes.put("approval.approver", approval.approver());
        attributes.put("policy.hash", approval.policyHash());
        attributes.put("policy.ids", String.join(",", approval.policies()));
        return attributes;
    }

    private static String fingerprint(Request request) {
        String joined = String.join("\u0000", request.agentId(), request.principalId(), request.action(),
                request.target() == null ? "" : request.target(), request.inputHash() == null ? "" : request.inputHash());
        return Hashes.sha256(joined.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
