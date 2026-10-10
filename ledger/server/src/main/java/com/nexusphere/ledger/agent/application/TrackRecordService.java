package com.nexusphere.ledger.agent.application;

import com.nexusphere.ledger.agent.domain.model.Agent;
import com.nexusphere.ledger.agent.domain.repository.AgentRepository;
import com.nexusphere.ledger.authorization.application.MandateService;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.evidence.domain.repository.CheckpointRepository;
import com.nexusphere.ledger.mandate.TrackRecordClaims;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.server.web.FieldErrors;
import com.nexusphere.ledger.server.web.LedgerException;
import com.nexusphere.ledger.transparency.application.IndexService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class TrackRecordService {

    public static final Duration DEFAULT_VALIDITY = Duration.ofDays(30);
    public static final Duration MAX_VALIDITY = Duration.ofDays(365);

    public record TrackRecordRequest(String audience, String validFor) {
    }

    public record IssuedTrackRecord(String credential, UUID id, Instant expiresAt, long checkpoint) {
    }

    private final AgentRepository agents;
    private final CheckpointRepository checkpoints;
    private final IndexService index;
    private final MandateService mandates;
    private final EvidenceService evidence;
    private final LedgerSigner signer;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    TrackRecordService(AgentRepository agents, CheckpointRepository checkpoints, IndexService index,
                       MandateService mandates, EvidenceService evidence, LedgerSigner signer,
                       NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.agents = agents;
        this.checkpoints = checkpoints;
        this.index = index;
        this.mandates = mandates;
        this.evidence = evidence;
        this.signer = signer;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public IssuedTrackRecord issue(Caller caller, String agentId, TrackRecordRequest request) {
        Agent agent = agents.find(agentId).filter(a -> caller.canActAs(a.agentId()))
                .orElseThrow(() -> LedgerException.notFound("Agent " + agentId));
        String audience = request == null ? null : request.audience();
        Duration validity = validity(request == null ? null : request.validFor(), audience);
        SignedCheckpoint checkpoint = checkpoints.latest()
                .orElseThrow(() -> LedgerException.notFound("A checkpoint"));
        long at = checkpoint.checkpoint().sequence();
        IndexService.SignedIndex signed = index.root(checkpoint);
        Map<String, Object> params = Map.of("agentId", agentId, "sequence", at);
        Map<String, Object> span = jdbc.queryForMap("""
                select count(*) as total, min(sequence) as first, max(sequence) as last,
                       count(distinct principal_ref) as principals
                from ledger.evidence_record where agent_id = :agentId and sequence <= :sequence
                """, params);
        long total = ((Number) span.get("total")).longValue();
        Long first = total == 0 ? null : ((Number) span.get("first")).longValue();
        Long last = total == 0 ? null : ((Number) span.get("last")).longValue();
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        UUID id = UUID.randomUUID();
        TrackRecordClaims claims = new TrackRecordClaims(mandates.issuer(), id, agentId, audience, now,
                now.plus(validity), at, checkpoint.checkpoint().headHash(), signed.root().root(), total, first, last,
                first == null ? null : recordedAt(first), last == null ? null : recordedAt(last),
                counts("outcome", params), ((Number) span.get("principals")).longValue(), counts("action", params),
                agent.publicSigningKey());
        String credential = signer.signTrackRecord(claims);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("checkpoint", String.valueOf(at));
        attributes.put("total", String.valueOf(total));
        attributes.put("expiresAt", claims.expiresAt().toString());
        if (audience != null) {
            attributes.put("audience", audience);
        }
        evidence.record(new EvidenceSubmission(clock.instant(), agentId, agent.ownerId(), "track-record/issue",
                id.toString(), null, null, null, Hashes.sha256(credential.getBytes(StandardCharsets.US_ASCII)), null,
                Outcome.SUCCEEDED, null, attributes));
        return new IssuedTrackRecord(credential, id, claims.expiresAt(), at);
    }

    private static Duration validity(String validFor, String audience) {
        FieldErrors errors = new FieldErrors().text("audience", audience, false, 300);
        Duration validity = DEFAULT_VALIDITY;
        if (validFor != null) {
            try {
                validity = Duration.parse(validFor);
            } catch (DateTimeParseException e) {
                validity = null;
            }
            if (validity == null || validity.compareTo(Duration.ofMinutes(1)) < 0
                    || validity.compareTo(MAX_VALIDITY) > 0) {
                errors.reject("validFor", "must be an ISO-8601 duration between PT1M and P365D");
            }
        }
        errors.throwIfAny("The track record request has invalid fields.");
        return validity;
    }

    private Instant recordedAt(long sequence) {
        return jdbc.queryForObject("select recorded_at from ledger.evidence_record where sequence = :sequence",
                Map.of("sequence", sequence), (rs, n) -> rs.getTimestamp("recorded_at").toInstant());
    }

    private Map<String, Long> counts(String column, Map<String, Object> params) {
        Map<String, Long> counts = new TreeMap<>();
        jdbc.query("select " + column + " as name, count(*) as count from ledger.evidence_record"
                + " where agent_id = :agentId and sequence <= :sequence group by " + column, params, rs -> {
            counts.put(rs.getString("name"), rs.getLong("count"));
        });
        return counts;
    }
}
