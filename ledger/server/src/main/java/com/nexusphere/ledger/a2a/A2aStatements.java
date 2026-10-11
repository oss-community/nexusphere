package com.nexusphere.ledger.a2a;

import com.nexusphere.ledger.authorization.application.MandateService;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.mandate.ExchangeStatement;
import com.nexusphere.ledger.mandate.Jws;
import com.nexusphere.ledger.mandate.Reconciliation;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class A2aStatements {

    static final String ACTION = "a2a/reconcile";
    static final Duration MAX_WINDOW = Duration.ofDays(31);
    private static final Duration MARGIN = Duration.ofHours(1);

    public record Signed(String token, ExchangeStatement statement) {
    }

    public record Reconciled(Reconciliation reconciliation, Signed ours, long evidenceSequence) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final LedgerProperties properties;
    private final MandateService mandates;
    private final LedgerSigner signer;
    private final A2aKeys keys;
    private final EvidenceService evidence;
    private final Clock clock;

    A2aStatements(NamedParameterJdbcTemplate jdbc, LedgerProperties properties, MandateService mandates,
                  LedgerSigner signer, A2aKeys keys, EvidenceService evidence, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.mandates = mandates;
        this.signer = signer;
        this.keys = keys;
        this.evidence = evidence;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Signed statement(String peer, Instant from, Instant to) {
        String issuer = issuerOf(peer);
        Instant start = from == null ? null : from.truncatedTo(ChronoUnit.SECONDS);
        Instant end = to == null ? null : to.truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> errors = new LinkedHashMap<>();
        if (start == null) {
            errors.put("from", "is required");
        }
        if (end == null) {
            errors.put("to", "is required");
        }
        if (start != null && end != null && (!start.isBefore(end) || Duration.between(start, end)
                .compareTo(MAX_WINDOW) > 0)) {
            errors.put("to", "must be after from and at most 31 days later");
        }
        if (!errors.isEmpty()) {
            throw LedgerException.invalid("The statement request has invalid fields.", errors);
        }
        return sign(build(issuer, start, end));
    }

    @Transactional
    public Reconciled reconcile(String token) {
        if (token == null || token.isBlank()) {
            throw LedgerException.invalid("The reconciliation request has invalid fields.",
                    Map.of("statement", "is required"));
        }
        ExchangeStatement theirs;
        try {
            String issuer = Jws.parse(token).payload().path("iss").asString("");
            issuerOf(issuer);
            theirs = ExchangeStatement.verified(token, issuer, keys);
        } catch (IllegalArgumentException e) {
            throw LedgerException.invalid("The peer statement cannot be trusted.", Map.of("statement", e.getMessage()));
        }
        if (!mandates.issuer().equals(theirs.peer())) {
            throw LedgerException.invalid("The peer statement cannot be trusted.",
                    Map.of("statement", "names " + theirs.peer() + ", not this ledger"));
        }
        Signed ours = sign(build(theirs.issuer(), theirs.from(), theirs.to()));
        Reconciliation reconciliation = Reconciliation.of(ours.statement(), theirs);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("from", theirs.from().toString());
        attributes.put("to", theirs.to().toString());
        attributes.put("matched", String.valueOf(reconciliation.matched()));
        attributes.put("undelivered", String.valueOf(reconciliation.undelivered()));
        attributes.put("discrepancies", String.valueOf(reconciliation.discrepancies().size()));
        EvidenceEntry entry = evidence.record(new EvidenceSubmission(clock.instant(), "ledger", "operator", ACTION,
                theirs.issuer(), null, reconciliation.reconciled() ? null
                : reconciliation.discrepancies().size() + " exchanges do not reconcile", null, hash(token),
                hash(ours.token()), reconciliation.reconciled() ? Outcome.SUCCEEDED : Outcome.FAILED, null,
                attributes));
        return new Reconciled(reconciliation, ours, entry.sequence());
    }

    private Signed sign(ExchangeStatement statement) {
        return new Signed(signer.signExchangeStatement(statement), statement);
    }

    private ExchangeStatement build(String issuer, Instant from, Instant to) {
        Set<String> names = new LinkedHashSet<>();
        peers().forEach((name, peer) -> {
            if (issuer.equals(strip(peer.issuer()))) {
                names.add(name);
            }
        });
        List<ExchangeStatement.Item> items = new ArrayList<>();
        jdbc.query("""
                select direction, request_id, method, mandate_id, request_token, request_hash, response_hash, status,
                       outcome, receipt from ledger.a2a_exchange
                where ((direction = 'OUTBOUND' and peer in (:names)) or (direction = 'INBOUND' and peer = :issuer))
                  and created_at >= :start and created_at < :end
                order by created_at
                """, new MapSqlParameterSource()
                .addValue("names", names.isEmpty() ? List.of("") : names)
                .addValue("issuer", issuer)
                .addValue("start", Timestamp.from(from.minus(MARGIN)))
                .addValue("end", Timestamp.from(to.plus(MARGIN))), rs -> {
            Instant requestedAt = Instant.ofEpochSecond(Jws.parse(rs.getString("request_token")).payload()
                    .path("iat").asLong());
            if (requestedAt.isBefore(from) || !requestedAt.isBefore(to)) {
                return;
            }
            if (items.size() == ExchangeStatement.MAX_EXCHANGES) {
                throw LedgerException.conflict("STATEMENT_TOO_LARGE", "More than "
                        + ExchangeStatement.MAX_EXCHANGES + " exchanges fall in the window; ask for a shorter one.");
            }
            String receipt = rs.getString("receipt");
            items.add(new ExchangeStatement.Item(rs.getString("direction"),
                    rs.getObject("request_id", UUID.class), requestedAt, rs.getString("method"),
                    rs.getObject("mandate_id", UUID.class), rs.getString("request_hash"),
                    rs.getString("response_hash"), rs.getObject("status", Integer.class), rs.getString("outcome"),
                    receipt == null ? null : hash(receipt)));
        });
        return new ExchangeStatement(mandates.issuer(), issuer, from, to, clock.instant().truncatedTo(ChronoUnit.SECONDS),
                items);
    }

    private String issuerOf(String peer) {
        if (peer != null) {
            LedgerProperties.A2a.Peer named = peers().get(peer);
            if (named != null && named.issuer() != null) {
                return strip(named.issuer());
            }
            String issuer = strip(peer);
            boolean known = peers().values().stream().anyMatch(p -> issuer.equals(strip(p.issuer())))
                    || trusted().contains(issuer);
            if (known) {
                return issuer;
            }
        }
        throw LedgerException.invalid("The statement request has invalid fields.",
                Map.of("peer", "must be a configured peer name or a trusted issuer"));
    }

    private Map<String, LedgerProperties.A2a.Peer> peers() {
        LedgerProperties.A2a a2a = properties.a2a();
        return a2a == null || a2a.peers() == null ? Map.of() : a2a.peers();
    }

    private List<String> trusted() {
        LedgerProperties.A2a a2a = properties.a2a();
        return a2a == null || a2a.trustedIssuers() == null ? List.of()
                : a2a.trustedIssuers().stream().filter(i -> !i.isBlank()).map(A2aStatements::strip).toList();
    }

    private static String strip(String issuer) {
        return issuer == null ? "" : issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }

    private static String hash(String token) {
        return Hashes.sha256(token.getBytes(StandardCharsets.US_ASCII));
    }
}
