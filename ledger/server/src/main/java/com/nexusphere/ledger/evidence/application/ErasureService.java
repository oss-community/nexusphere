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
                          boolean legalHold, boolean completed, UUID evidenceId) {
    }

    private record Pass(long erased, long retained, Instant retainedUntil) {
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
        int open = openGrants(principalId);
        if (open > 0) {
            throw LedgerException.conflict("GRANTS_OPEN", "The principal still has " + open
                    + " active or pending grants; revoke or deny them first.");
        }
        Instant now = clock.instant();
        boolean held = held(ref);
        Pass pass = held ? retainAll(ref) : shred(ref, now);
        boolean completed = !held && pass.retained() == 0;
        jdbc.update("""
                insert into ledger.principal_erasure (id, principal_ref, requested_at, reason, completed_at)
                values (:id, :ref, :now, :reason, :completed)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("ref", ref)
                .addValue("now", Timestamp.from(now)).addValue("reason", reason)
                .addValue("completed", completed ? Timestamp.from(now) : null));
        if (completed) {
            complete(principalId, ref, now);
        }
        EvidenceEntry entry = record(ref, reason, pass, held, completed, now);
        return new Erasure(ref, pass.erased(), pass.retained(), pass.retainedUntil(), held, completed, entry.id());
    }

    @Transactional
    public List<Erasure> continuePending() {
        repository.lockHead();
        Instant now = clock.instant();
        List<Erasure> done = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select distinct e.principal_ref, k.principal_id from ledger.principal_erasure e
                join ledger.principal_key k on k.principal_ref = e.principal_ref
                where e.completed_at is null and k.principal_id is not null
                and not exists (select 1 from ledger.legal_hold h
                                where h.principal_ref = e.principal_ref and h.released_at is null)
                """, Map.of())) {
            UUID ref = (UUID) row.get("principal_ref");
            String principalId = (String) row.get("principal_id");
            Pass pass = shred(ref, now);
            boolean completed = pass.retained() == 0 && openGrants(principalId) == 0;
            if (pass.erased() == 0 && !completed) {
                continue;
            }
            if (completed) {
                jdbc.update("""
                        update ledger.principal_erasure set completed_at = :now
                        where principal_ref = :ref and completed_at is null
                        """, Map.of("ref", ref, "now", Timestamp.from(now)));
                complete(principalId, ref, now);
            }
            EvidenceEntry entry = record(ref, "retention ended", pass, false, completed, now);
            done.add(new Erasure(ref, pass.erased(), pass.retained(), pass.retainedUntil(), false, completed,
                    entry.id()));
        }
        return done;
    }

    boolean held(UUID ref) {
        Integer holds = jdbc.queryForObject("""
                select count(*) from ledger.legal_hold where principal_ref = :ref and released_at is null
                """, Map.of("ref", ref), Integer.class);
        return holds != null && holds > 0;
    }

    private int openGrants(String principalId) {
        Integer open = jdbc.queryForObject("""
                select count(*) from ledger.grant_record
                where principal_id = :principalId and status in ('ACTIVE', 'PENDING')
                """, Map.of("principalId", principalId), Integer.class);
        return open == null ? 0 : open;
    }

    private Pass retainAll(UUID ref) {
        Long count = jdbc.queryForObject("""
                select count(*) from ledger.evidence_personal where principal_ref = :ref
                """, Map.of("ref", ref), Long.class);
        return new Pass(0, count == null ? 0 : count, null);
    }

    private Pass shred(UUID ref, Instant now) {
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
        return new Pass(erasable.size(), retained, retainedUntil);
    }

    private EvidenceEntry record(UUID ref, String reason, Pass pass, boolean held, boolean completed, Instant now) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("erasedEntries", Long.toString(pass.erased()));
        attributes.put("retainedEntries", Long.toString(pass.retained()));
        if (pass.retainedUntil() != null) {
            attributes.put("retainedUntil", pass.retainedUntil().toString());
        }
        if (held) {
            attributes.put("legalHold", "true");
        }
        attributes.put("completed", Boolean.toString(completed));
        return evidence.record(new EvidenceSubmission(now, "ledger", "operator", ACTION, "principal:" + ref, null,
                reason, null, null, null, Outcome.SUCCEEDED, null, attributes));
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
