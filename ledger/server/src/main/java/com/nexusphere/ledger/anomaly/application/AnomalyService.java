package com.nexusphere.ledger.anomaly.application;

import com.nexusphere.ledger.anomaly.application.AnomalyStatistics.Finding;
import com.nexusphere.ledger.anomaly.application.AnomalyStatistics.Kind;
import com.nexusphere.ledger.chain.Timestamps;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.web.LedgerException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AnomalyService {

    public static final String SYSTEM_AGENT = "ledger";
    public static final int MAX_LIMIT = 500;

    private record Counts(long total, long decisions, long denials, long outcomes, long failures) {

        static final Counts NONE = new Counts(0, 0, 0, 0, 0);
    }

    private record Baseline(Counts counts, long[] buckets, Instant firstSeen) {
    }

    private static final String COUNTS = """
            select agent_id, count(*) as total,
                count(*) filter (where decision is not null) as decisions,
                count(*) filter (where decision = 'DENY') as denials,
                count(*) filter (where outcome in ('SUCCEEDED', 'FAILED')) as outcomes,
                count(*) filter (where outcome = 'FAILED') as failures,
                min(recorded_at) as first_seen
            from ledger.evidence_record
            where recorded_at >= :from and recorded_at < :to and agent_id <> 'ledger' and action not like 'anomaly/%'
            group by agent_id
            """;
    private static final String COLUMNS = """
            id, agent_id, kind, status, observed, expected, score, window_start, window_end, detected_at,
            resolved_at, evidence_id, resolved_evidence_id
            """;
    private static final RowMapper<Anomaly> ROWS = (rs, n) -> new Anomaly(rs.getObject("id", UUID.class),
            rs.getString("agent_id"), Kind.valueOf(rs.getString("kind")),
            Anomaly.Status.valueOf(rs.getString("status")), rs.getDouble("observed"), rs.getDouble("expected"),
            rs.getDouble("score"), rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(), rs.getTimestamp("detected_at").toInstant(),
            rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
            rs.getObject("evidence_id", UUID.class), rs.getObject("resolved_evidence_id", UUID.class));

    private final NamedParameterJdbcTemplate jdbc;
    private final EvidenceService evidence;
    private final Clock clock;
    private final LedgerProperties.Anomaly settings;
    private final MultiGauge scores;
    private final MeterRegistry registry;

    AnomalyService(NamedParameterJdbcTemplate jdbc, EvidenceService evidence, LedgerProperties properties,
                   MeterRegistry registry, Clock clock) {
        this.jdbc = jdbc;
        this.evidence = evidence;
        this.clock = clock;
        this.registry = registry;
        this.settings = properties.anomaly() == null
                ? new LedgerProperties.Anomaly(true, Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofDays(7),
                Duration.ofDays(1), 3, 10) : properties.anomaly();
        if (settings.window() == null || settings.baseline() == null || settings.window().isNegative()
                || settings.window().isZero() || settings.baseline().compareTo(settings.window()) < 0) {
            throw new IllegalStateException("ledger.anomaly.baseline must be at least ledger.anomaly.window");
        }
        if (settings.threshold() <= 0 || settings.minEvents() < 1) {
            throw new IllegalStateException("ledger.anomaly.threshold and min-events must be positive");
        }
        this.scores = MultiGauge.builder("ledger.anomaly.score")
                .description("Score of each open anomaly, in standard deviations above the agent's baseline")
                .register(registry);
    }

    public boolean enabled() {
        return settings.enabled();
    }

    @Transactional
    public List<Anomaly> detect() {
        Instant now = clock.instant();
        Instant windowStart = now.minus(settings.window());
        Instant baselineStart = windowStart.minus(settings.baseline());
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended('ledger.anomaly', 0)) is null", Map.of(),
                Boolean.class);
        Map<String, Counts> window = counts(windowStart, now);
        Map<String, Baseline> baselines = baselines(baselineStart, windowStart, window.keySet());
        Map<String, Anomaly> open = new HashMap<>();
        for (Anomaly a : list(Anomaly.Status.OPEN, null, MAX_LIMIT)) {
            open.put(a.agentId() + "/" + a.kind(), a);
        }
        List<Anomaly> detected = new ArrayList<>();
        for (Map.Entry<String, Counts> agent : window.entrySet()) {
            Baseline baseline = baselines.get(agent.getKey());
            if (baseline == null || baseline.firstSeen() == null
                    || Duration.between(baseline.firstSeen(), windowStart).compareTo(settings.minHistory()) < 0) {
                continue;
            }
            for (Finding finding : findings(agent.getValue(), baseline)) {
                Anomaly existing = open.remove(agent.getKey() + "/" + finding.kind());
                if (existing == null) {
                    detected.add(open(agent.getKey(), finding, windowStart, now));
                }
            }
        }
        open.values().forEach(a -> resolve(a, now));
        refreshMetrics();
        return detected;
    }

    @Transactional(readOnly = true)
    public List<Anomaly> list(Anomaly.Status status, String agentId, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw LedgerException.invalid("The query has invalid parameters.",
                    Map.of("limit", "must be between 1 and " + MAX_LIMIT));
        }
        return jdbc.query("select " + COLUMNS + """
                from ledger.anomaly
                where (cast(:status as varchar) is null or status = :status)
                  and (cast(:agentId as varchar) is null or agent_id = :agentId)
                order by detected_at desc limit :limit
                """, new MapSqlParameterSource()
                .addValue("status", status == null ? null : status.name())
                .addValue("agentId", agentId)
                .addValue("limit", limit), ROWS);
    }

    @Transactional(readOnly = true)
    public void refreshMetrics() {
        scores.register(list(Anomaly.Status.OPEN, null, MAX_LIMIT).stream()
                .map(a -> MultiGauge.Row.of(Tags.of("agent", a.agentId(), "kind", a.kind().name()), a.score()))
                .toList(), true);
    }

    private List<Finding> findings(Counts now, Baseline baseline) {
        List<Finding> findings = new ArrayList<>();
        AnomalyStatistics.volume(now.total(), baseline.buckets(), settings.threshold(), settings.minEvents())
                .ifPresent(findings::add);
        AnomalyStatistics.rate(Kind.DENIALS, now.denials(), now.decisions(), baseline.counts().denials(),
                baseline.counts().decisions(), settings.threshold(), settings.minEvents()).ifPresent(findings::add);
        AnomalyStatistics.rate(Kind.FAILURES, now.failures(), now.outcomes(), baseline.counts().failures(),
                baseline.counts().outcomes(), settings.threshold(), settings.minEvents()).ifPresent(findings::add);
        return findings;
    }

    private Map<String, Counts> counts(Instant from, Instant to) {
        Map<String, Counts> counts = new HashMap<>();
        jdbc.query(COUNTS, Map.of("from", Timestamp.from(from), "to", Timestamp.from(to)),
                rs -> {
                    counts.put(rs.getString("agent_id"), new Counts(rs.getLong("total"), rs.getLong("decisions"),
                            rs.getLong("denials"), rs.getLong("outcomes"), rs.getLong("failures")));
                });
        return counts;
    }

    private Map<String, Baseline> baselines(Instant from, Instant to, Set<String> agents) {
        Map<String, Baseline> baselines = new HashMap<>();
        if (agents.isEmpty()) {
            return baselines;
        }
        int size = (int) (settings.baseline().toMillis() / settings.window().toMillis());
        Map<String, long[]> buckets = new HashMap<>();
        jdbc.query("""
                select agent_id, floor(extract(epoch from (:to - recorded_at)) / :seconds) as bucket, count(*) as n
                from ledger.evidence_record
                where recorded_at >= :from and recorded_at < :to and agent_id in (:agents)
                  and action not like 'anomaly/%'
                group by agent_id, bucket
                """, new MapSqlParameterSource()
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to))
                .addValue("seconds", (double) settings.window().toSeconds())
                .addValue("agents", agents), rs -> {
                    int bucket = (int) rs.getLong("bucket");
                    if (bucket >= 0 && bucket < size) {
                        buckets.computeIfAbsent(rs.getString("agent_id"), a -> new long[size])[bucket] = rs.getLong("n");
                    }
                });
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to));
        jdbc.query(COUNTS, params, rs -> {
            String agent = rs.getString("agent_id");
            if (agents.contains(agent)) {
                baselines.put(agent, new Baseline(new Counts(rs.getLong("total"), rs.getLong("decisions"),
                        rs.getLong("denials"), rs.getLong("outcomes"), rs.getLong("failures")),
                        buckets.getOrDefault(agent, new long[size]), rs.getTimestamp("first_seen").toInstant()));
            }
        });
        for (String agent : agents) {
            if (!baselines.containsKey(agent) && !settings.minHistory().isPositive()) {
                baselines.put(agent, new Baseline(Counts.NONE, new long[size], from));
            }
        }
        return baselines;
    }

    private Anomaly open(String agentId, Finding finding, Instant windowStart, Instant windowEnd) {
        UUID id = UUID.randomUUID();
        Map<String, String> attributes = attributes(id, finding.kind(), windowStart, windowEnd);
        attributes.put("anomaly.observed", number(finding.observed()));
        attributes.put("anomaly.expected", number(finding.expected()));
        attributes.put("anomaly.score", number(finding.score()));
        UUID evidenceId = evidence.record(new EvidenceSubmission(windowEnd, agentId, SYSTEM_AGENT, "anomaly/detect",
                finding.kind().name(), null, describe(agentId, finding), null, null, null, Outcome.SUCCEEDED, null,
                attributes)).id();
        jdbc.update("""
                insert into ledger.anomaly (id, agent_id, kind, status, observed, expected, score, window_start,
                    window_end, detected_at, evidence_id)
                values (:id, :agentId, :kind, 'OPEN', :observed, :expected, :score, :windowStart, :windowEnd,
                    :windowEnd, :evidenceId)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("agentId", agentId)
                .addValue("kind", finding.kind().name())
                .addValue("observed", finding.observed())
                .addValue("expected", finding.expected())
                .addValue("score", finding.score())
                .addValue("windowStart", Timestamp.from(windowStart))
                .addValue("windowEnd", Timestamp.from(windowEnd))
                .addValue("evidenceId", evidenceId));
        Counter.builder("ledger.anomalies.detected").description("Anomalies detected")
                .tag("kind", finding.kind().name()).register(registry).increment();
        return jdbc.query("select " + COLUMNS + " from ledger.anomaly where id = :id", Map.of("id", id), ROWS)
                .getFirst();
    }

    private void resolve(Anomaly anomaly, Instant now) {
        UUID evidenceId = evidence.record(new EvidenceSubmission(now, anomaly.agentId(), SYSTEM_AGENT,
                "anomaly/resolve", anomaly.kind().name(), null, "The " + label(anomaly.kind()) + " of agent "
                + anomaly.agentId() + " is back within its baseline.", null, null, null, Outcome.SUCCEEDED, null,
                attributes(anomaly.id(), anomaly.kind(), anomaly.windowStart(), anomaly.windowEnd()))).id();
        jdbc.update("""
                update ledger.anomaly set status = 'RESOLVED', resolved_at = :now, resolved_evidence_id = :evidenceId
                where id = :id
                """, Map.of("now", Timestamp.from(now), "evidenceId", evidenceId, "id", anomaly.id()));
    }

    private static Map<String, String> attributes(UUID id, Kind kind, Instant windowStart, Instant windowEnd) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("anomaly.id", id.toString());
        attributes.put("anomaly.kind", kind.name());
        attributes.put("anomaly.windowStart", Timestamps.format(windowStart));
        attributes.put("anomaly.windowEnd", Timestamps.format(windowEnd));
        return attributes;
    }

    private static String describe(String agentId, Finding finding) {
        if (finding.kind() == Kind.VOLUME) {
            return String.format(Locale.ROOT, "Agent %s recorded %.0f entries in the window against %.1f usually,"
                    + " %.1f standard deviations above its baseline.", agentId, finding.observed(), finding.expected(),
                    finding.score());
        }
        return String.format(Locale.ROOT, "Agent %s has %.0f%% %s in the window against %.0f%% usually,"
                        + " %.1f standard deviations above its baseline.", agentId, finding.observed() * 100,
                label(finding.kind()), finding.expected() * 100, finding.score());
    }

    private static String label(Kind kind) {
        return switch (kind) {
            case VOLUME -> "volume";
            case DENIALS -> "denials";
            case FAILURES -> "failures";
        };
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    @Transactional(readOnly = true)
    public Optional<Anomaly> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from ledger.anomaly where id = :id", Map.of("id", id), ROWS)
                .stream().findFirst();
    }
}
