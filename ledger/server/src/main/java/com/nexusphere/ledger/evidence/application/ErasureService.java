package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.compliance.application.Compliance;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ErasureService {

    public static final String ACTION = "principal/erase";
    private static final String ERASED = "erased:";

    private final NamedParameterJdbcTemplate jdbc;
    private final EvidenceRepository repository;
    private final EvidenceService evidence;
    private final Compliance compliance;
    private final Clock clock;

    ErasureService(NamedParameterJdbcTemplate jdbc, EvidenceRepository repository, EvidenceService evidence,
                   Compliance compliance, Clock clock) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.evidence = evidence;
        this.compliance = compliance;
        this.clock = clock;
    }

    public record Erasure(UUID principalRef, long erasedEntries, long retainedEntries, Instant retainedUntil,
                          boolean completed, UUID evidenceId) {
    }

    @Transactional
    public Erasure erase(String principalId, String reason) {
        if (reason != null && reason.length() > 500) {
            throw LedgerException.invalid("The erasure has invalid fields.",
                    Map.of("reason", "must be at most 500 characters"));
        }
        repository.lockHead();
        List<UUID> refs = jdbc.queryForList("""
                select principal_ref from ledger.principal_key where principal_id = :principalId for update
                """, Map.of("principalId", principalId), UUID.class);
        if (refs.isEmpty()) {
            throw LedgerException.notFound("Evidence about the principal");
        }
        UUID ref = refs.getFirst();
        Integer open = jdbc.queryForObject("""
                select count(*) from ledger.grant_record
                where principal_id = :principalId and status in ('ACTIVE', 'PENDING')
                """, Map.of("principalId", principalId), Integer.class);
        if (open != null && open > 0) {
            throw LedgerException.conflict("GRANTS_OPEN", "The principal still has " + open
                    + " active or pending grants; revoke or deny them first.");
        }
        Instant now = clock.instant();
        List<UUID> erasable = new ArrayList<>();
        long retained = 0;
        Instant retainedUntil = null;
        for (Map<String, Object> row : jdbc.queryForList("""
                select p.evidence_id, r.action, r.occurred_at from ledger.evidence_personal p
                join ledger.evidence_record r on r.id = p.evidence_id where p.principal_ref = :ref
                """, Map.of("ref", ref))) {
            Instant occurredAt = ((Timestamp) row.get("occurred_at")).toInstant();
            Instant until = compliance.retention((String) row.get("action"))
                    .map(period -> occurredAt.atOffset(ZoneOffset.UTC).plus(period).toInstant())
                    .orElse(occurredAt);
            if (until.isAfter(now)) {
                retained++;
                retainedUntil = retainedUntil == null || until.isAfter(retainedUntil) ? until : retainedUntil;
            } else {
                erasable.add((UUID) row.get("evidence_id"));
            }
        }
        if (!erasable.isEmpty()) {
            jdbc.update("delete from ledger.evidence_personal where evidence_id in (:ids)", Map.of("ids", erasable));
        }
        boolean completed = retained == 0;
        jdbc.update("""
                insert into ledger.principal_erasure (id, principal_ref, requested_at, reason, completed_at)
                values (:id, :ref, :now, :reason, :completed)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("ref", ref)
                .addValue("now", Timestamp.from(now)).addValue("reason", reason)
                .addValue("completed", completed ? Timestamp.from(now) : null));
        if (completed) {
            complete(principalId, ref, now);
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("erasedEntries", Long.toString(erasable.size()));
        attributes.put("retainedEntries", Long.toString(retained));
        if (retainedUntil != null) {
            attributes.put("retainedUntil", retainedUntil.toString());
        }
        EvidenceEntry entry = evidence.record(new EvidenceSubmission(now, "ledger", "operator", ACTION,
                "principal:" + ref, null, reason, null, null, null, Outcome.SUCCEEDED, null, attributes));
        return new Erasure(ref, erasable.size(), retained, retainedUntil, completed, entry.id());
    }

    private void complete(String principalId, UUID ref, Instant now) {
        Map<String, Object> params = Map.of("principalId", principalId, "erased", ERASED + ref,
                "now", Timestamp.from(now), "ref", ref);
        jdbc.update("""
                update ledger.principal_key set data_key = null, principal_id = null, erased_at = :now
                where principal_ref = :ref
                """, params);
        jdbc.update("update ledger.grant_record set principal_id = :erased where principal_id = :principalId",
                params);
        jdbc.update("update ledger.decision set principal_id = :erased where principal_id = :principalId", params);
        jdbc.update("""
                update ledger.mandate set principal_id = :erased, token = '' where principal_id = :principalId
                """, params);
        jdbc.update("""
                update ledger.a2a_exchange set principal_id = :erased, mandate_token = '', request_token = ''
                where principal_id = :principalId
                """, params);
    }
}
