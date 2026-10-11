package com.nexusphere.ledger.policy.application;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.config.LedgerProperties;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PolicyService {

    public static final int MAX_BYTES = 1_048_576;
    public static final String HASH_ATTRIBUTE = "policy.hash";
    public static final String IDS_ATTRIBUTE = "policy.ids";
    public static final String EFFECT_ATTRIBUTE = "policy.defaultEffect";

    public record PolicySetText(String hash, String text, List<String> policies, Instant createdAt, String createdBy) {
    }

    public record Active(PolicySetText set, Instant activatedAt, String activatedBy) {
    }

    public record Evaluated(String hash, CedarPolicies.Verdict verdict) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final EvidenceService evidence;
    private final Clock clock;
    private final boolean defaultPermit;
    private final Map<String, CedarPolicies> compiled = new ConcurrentHashMap<>();

    PolicyService(NamedParameterJdbcTemplate jdbc, EvidenceService evidence, LedgerProperties properties,
                  Clock clock) {
        this.jdbc = jdbc;
        this.evidence = evidence;
        this.clock = clock;
        String effect = properties.policy() == null || properties.policy().defaultEffect() == null ? "permit"
                : properties.policy().defaultEffect().trim();
        if (!effect.equals("permit") && !effect.equals("deny")) {
            throw new IllegalStateException("ledger.policy.default-effect must be permit or deny");
        }
        this.defaultPermit = effect.equals("permit");
    }

    public String defaultEffect() {
        return defaultPermit ? "permit" : "deny";
    }

    @Transactional
    public Active activate(String text, String activatedBy) {
        if (text == null) {
            throw LedgerException.invalid("The policies have invalid fields.", Map.of("text", "is required"));
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw LedgerException.invalid("The policies have invalid fields.",
                    Map.of("text", "must be at most " + MAX_BYTES + " bytes"));
        }
        CedarPolicies policies;
        try {
            policies = CedarPolicies.compile(text, defaultPermit);
        } catch (IllegalArgumentException e) {
            throw LedgerException.invalid("The policies have invalid fields.", Map.of("text", e.getMessage()));
        }
        String hash = Hashes.sha256(bytes);
        compiled.put(hash, policies);
        Instant now = clock.instant();
        jdbc.update("""
                insert into ledger.policy_set (hash, text, policies, created_at, created_by)
                values (:hash, :text, :policies, :createdAt, :createdBy)
                on conflict (hash) do nothing
                """, new MapSqlParameterSource()
                .addValue("hash", hash)
                .addValue("text", text)
                .addValue("policies", policies.ids().size())
                .addValue("createdAt", Timestamp.from(now))
                .addValue("createdBy", activatedBy));
        jdbc.update("""
                insert into ledger.policy_activation (hash, activated_at, activated_by)
                values (:hash, :activatedAt, :activatedBy)
                """, new MapSqlParameterSource()
                .addValue("hash", hash)
                .addValue("activatedAt", Timestamp.from(now))
                .addValue("activatedBy", activatedBy));
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(HASH_ATTRIBUTE, hash);
        attributes.put("policy.count", String.valueOf(policies.ids().size()));
        attributes.put(EFFECT_ATTRIBUTE, defaultEffect());
        evidence.record(new EvidenceSubmission(now, "ledger", "operator", "policy/activate", hash, null, null, null,
                hash, null, Outcome.SUCCEEDED, null, attributes));
        return active().orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<Active> active() {
        return jdbc.query("""
                select s.hash, s.text, s.created_at, s.created_by, a.activated_at, a.activated_by
                from ledger.policy_activation a join ledger.policy_set s on s.hash = a.hash
                order by a.id desc limit 1
                """, Map.of(), (rs, n) -> new Active(text(rs.getString("hash"), rs.getString("text"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("created_by")),
                rs.getTimestamp("activated_at").toInstant(), rs.getString("activated_by"))).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public PolicySetText get(String hash) {
        return jdbc.query("select hash, text, created_at, created_by from ledger.policy_set where hash = :hash",
                Map.of("hash", hash), (rs, n) -> text(rs.getString("hash"), rs.getString("text"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"))).stream().findFirst()
                .orElseThrow(() -> LedgerException.notFound("Policy set " + hash));
    }

    @Transactional(readOnly = true)
    public Map<String, String> texts(Collection<String> hashes) {
        Map<String, String> texts = new TreeMap<>();
        if (!hashes.isEmpty()) {
            jdbc.query("select hash, text from ledger.policy_set where hash in (:hashes)", Map.of("hashes", hashes),
                    rs -> {
                        texts.put(rs.getString("hash"), rs.getString("text"));
                    });
        }
        return texts;
    }

    public Optional<Evaluated> evaluate(CedarPolicies.Request request) {
        Optional<String> hash = jdbc.query("""
                select hash from ledger.policy_activation order by id desc limit 1
                """, Map.of(), (rs, n) -> rs.getString("hash")).stream().findFirst();
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        CedarPolicies policies = compiled.computeIfAbsent(hash.get(), h -> CedarPolicies.compile(jdbc.queryForObject(
                "select text from ledger.policy_set where hash = :hash", Map.of("hash", h), String.class),
                defaultPermit));
        return Optional.of(new Evaluated(hash.get(), policies.evaluate(request)));
    }

    private PolicySetText text(String hash, String text, Instant createdAt, String createdBy) {
        CedarPolicies policies = compiled.computeIfAbsent(hash, h -> CedarPolicies.compile(text, defaultPermit));
        return new PolicySetText(hash, text, policies.ids(), createdAt, createdBy);
    }
}
