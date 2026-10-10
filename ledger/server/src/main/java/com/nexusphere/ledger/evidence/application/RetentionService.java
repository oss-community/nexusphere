package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.compliance.application.Compliance;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RetentionService {

    public static final String ACTION = "retention/expire";
    private static final int BATCH = 5000;

    private final NamedParameterJdbcTemplate jdbc;
    private final EvidenceRepository repository;
    private final EvidenceService evidence;
    private final ErasureService erasures;
    private final Compliance compliance;
    private final Clock clock;

    RetentionService(NamedParameterJdbcTemplate jdbc, EvidenceRepository repository, EvidenceService evidence,
                     ErasureService erasures, Compliance compliance, Clock clock) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.evidence = evidence;
        this.erasures = erasures;
        this.compliance = compliance;
        this.clock = clock;
    }

    public record Sweep(long expiredEntries, List<ErasureService.Erasure> erasures) {
    }

    @Transactional
    public Sweep sweep() {
        repository.lockHead();
        long expired = expire(clock.instant());
        return new Sweep(expired, erasures.continuePending());
    }

    private long expire(Instant now) {
        Period shortest = compliance.shortestMaximum().orElse(null);
        if (shortest == null) {
            return 0;
        }
        Instant cutoff = now.atOffset(ZoneOffset.UTC).minus(shortest).toInstant();
        long expired = 0;
        int offset = 0;
        List<Map<String, Object>> rows;
        do {
            rows = jdbc.queryForList("""
                    select p.evidence_id, r.action, r.occurred_at from ledger.evidence_personal p
                    join ledger.evidence_record r on r.id = p.evidence_id
                    where r.occurred_at < :cutoff
                    and not exists (select 1 from ledger.legal_hold h
                                    where h.principal_ref = p.principal_ref and h.released_at is null)
                    order by r.occurred_at, p.evidence_id limit :limit offset :offset
                    """, Map.of("cutoff", Timestamp.from(cutoff), "limit", BATCH, "offset", offset));
            List<UUID> due = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Instant occurredAt = ((Timestamp) row.get("occurred_at")).toInstant();
                boolean passed = compliance.maximum((String) row.get("action"))
                        .map(period -> !occurredAt.atOffset(ZoneOffset.UTC).plus(period).toInstant().isAfter(now))
                        .orElse(false);
                if (passed) {
                    due.add((UUID) row.get("evidence_id"));
                }
            }
            if (!due.isEmpty()) {
                jdbc.update("delete from ledger.evidence_personal where evidence_id in (:ids)", Map.of("ids", due));
                expired += due.size();
            }
            offset += rows.size() - due.size();
        } while (rows.size() == BATCH);
        if (expired > 0) {
            evidence.record(new EvidenceSubmission(now, "ledger", "operator", ACTION, null, null,
                    "maximum retention of the compliance profiles", null, null, null, Outcome.SUCCEEDED, null,
                    Map.of("expiredEntries", Long.toString(expired))));
        }
        return expired;
    }
}
