package com.nexusphere.ledger.a2a;

import com.nexusphere.ledger.authorization.application.MandateService;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.Decision;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.mandate.ExchangeReceipt;
import com.nexusphere.ledger.mandate.ExchangeRequest;
import com.nexusphere.ledger.mandate.Jws;
import com.nexusphere.ledger.mandate.MandateCheck;
import com.nexusphere.ledger.mandate.MandateClaims;
import com.nexusphere.ledger.mandate.MandateProblem;
import com.nexusphere.ledger.mandate.MandateVerifier;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.server.web.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
class A2aInbound {

    static final String ACTION = "a2a/receive";
    static final String USES_EXHAUSTED = "USES_EXHAUSTED";

    private static final Logger log = LoggerFactory.getLogger(A2aInbound.class);
    private static final Set<MandateProblem> UNAUTHENTICATED = EnumSet.of(MandateProblem.MALFORMED,
            MandateProblem.WRONG_TYPE, MandateProblem.UNTRUSTED_ISSUER, MandateProblem.UNKNOWN_KEY,
            MandateProblem.BAD_SIGNATURE);

    private final LedgerProperties properties;
    private final MandateService mandates;
    private final EvidenceService evidence;
    private final LedgerSigner signer;
    private final A2aKeys keys;
    private final A2aHttp http;
    private final A2aExchanges exchanges;
    private final JsonRpc rpc;
    private final Clock clock;

    A2aInbound(LedgerProperties properties, MandateService mandates, EvidenceService evidence, LedgerSigner signer,
               A2aKeys keys, A2aHttp http, A2aExchanges exchanges, JsonRpc rpc, Clock clock) {
        this.properties = properties;
        this.mandates = mandates;
        this.evidence = evidence;
        this.signer = signer;
        this.keys = keys;
        this.http = http;
        this.exchanges = exchanges;
        this.rpc = rpc;
        this.clock = clock;
    }

    A2aResponse receive(String agentName, String mandateToken, String requestToken, byte[] body) {
        LedgerProperties.A2a.Agent agent = agent(agentName);
        A2aResponse invalid = rpc.invalid(body);
        if (invalid != null) {
            return invalid;
        }
        JsonNode message = rpc.read(body).orElseThrow();
        JsonNode id = message.get("id");
        String method = message.path("method").asString();
        MandateVerifier verifier = keys.verifier().orElse(null);
        if (verifier == null) {
            return rpc.error(403, id, JsonRpc.DENIED, "This ledger trusts no mandate issuers.", null, Map.of());
        }
        if (mandateToken == null || requestToken == null) {
            return unauthenticated(id, "A2A requests need the " + A2aOutbound.MANDATE_HEADER + " and "
                    + A2aOutbound.REQUEST_HEADER + " headers.", List.of());
        }
        MandateCheck check = verifier.verify(mandateToken);
        List<String> fatal = check.problems().stream().filter(p -> UNAUTHENTICATED.contains(p.code()))
                .map(p -> p.code().name()).toList();
        if (check.claims() == null || !fatal.isEmpty()) {
            return unauthenticated(id, "The mandate cannot be trusted.", fatal);
        }
        MandateClaims claims = check.claims();
        String requestHash = Hashes.sha256(body);
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        ExchangeRequest request;
        try {
            request = ExchangeRequest.fromPayload(Jws.verified(requestToken, ExchangeRequest.TYPE, claims.issuer(),
                    keys).payload());
        } catch (RuntimeException e) {
            return unauthenticated(id, "The request proof cannot be trusted: " + e.getMessage(), List.of());
        }
        Duration maxAge = maxAge();
        if (!request.mandateId().equals(claims.mandateId()) || !request.agentId().equals(claims.agentId())
                || !mandates.issuer().equals(request.audience()) || !method.equals(request.method())
                || !requestHash.equals(request.requestHash())
                || Duration.between(request.issuedAt(), now).abs().compareTo(maxAge) > 0) {
            return unauthenticated(id, "The request proof does not match this request.", List.of());
        }
        UUID exchangeId = UUID.randomUUID();
        if (!exchanges.reserve(new A2aExchange(exchangeId, A2aExchange.INBOUND, claims.issuer(), request.requestId(),
                agentName, claims.principalId(), method, claims.mandateId(), mandateToken, requestToken, requestHash,
                null, null, null, null, null, null, now))) {
            return rpc.error(409, id, JsonRpc.DENIED, "This request has already been received.", null, Map.of());
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a2a.issuer", claims.issuer());
        attributes.put("a2a.sender", claims.agentId());
        attributes.put("a2a.grant", claims.grantId().toString());
        attributes.put("a2a.termsHash", claims.termsHash());
        attributes.put("a2a.method", method);
        attributes.put("a2a.exchange", exchangeId.toString());
        List<String> problems = new ArrayList<>(check.problems().stream().map(p -> p.code().name()).toList());
        if (!claims.coversAction(A2aOutbound.ACTION)) {
            problems.add(MandateProblem.NOT_COVERED.name());
        }
        if (problems.isEmpty() && claims.maxUses() != null
                && !exchanges.claimUse(exchangeId, claims.issuer(), claims.mandateId(), claims.maxUses())) {
            problems.add(USES_EXHAUSTED);
        }
        if (!problems.isEmpty()) {
            EvidenceEntry denied = record(agentName, claims, method, request, Decision.DENY, Outcome.DENIED,
                    String.join(", ", problems), null, attributes);
            exchanges.complete(exchangeId, null, 403, Outcome.DENIED.name(), denied.sequence(), null, null);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("problems", problems);
            data.put("evidenceSequence", denied.sequence());
            return rpc.error(403, id, JsonRpc.DENIED, "The mandate does not allow this request.", data,
                    Map.of(A2aOutbound.EXCHANGE_HEADER, exchangeId.toString()));
        }
        int status;
        byte[] answer;
        try {
            A2aHttp.Reply reply = http.post(agent.url(), timeout(), Map.of("Authorization",
                    agent.authorization() == null ? "" : agent.authorization()), body);
            status = reply.status();
            answer = reply.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("A2A agent {} is unavailable: {}", agent.url(), e.getMessage());
            exchanges.releaseUse(exchangeId);
            A2aResponse failed = rpc.error(502, id, JsonRpc.UPSTREAM_UNAVAILABLE, "The agent is unavailable.", null,
                    Map.of());
            status = failed.status();
            answer = failed.body();
        }
        boolean failed = rpc.failed(status, answer);
        Outcome outcome = failed ? Outcome.FAILED : Outcome.SUCCEEDED;
        String responseHash = Hashes.sha256(answer);
        attributes.put("a2a.status", String.valueOf(status));
        EvidenceEntry entry = record(agentName, claims, method, request, Decision.ALLOW, outcome,
                failed ? "The agent answered with HTTP " + status + " or a JSON-RPC error." : null, responseHash,
                attributes);
        String receipt = signer.sign(ExchangeReceipt.TYPE, new ExchangeReceipt(mandates.issuer(), claims.issuer(),
                UUID.randomUUID(), request.requestId(), claims.mandateId(), agentName, requestHash, responseHash,
                status, outcome.name(), entry.sequence(), entry.hash(), now).toPayload());
        exchanges.complete(exchangeId, responseHash, status, outcome.name(), entry.sequence(), receipt, "ISSUED");
        return new A2aResponse(status, answer, Map.of(A2aOutbound.RECEIPT_HEADER, receipt,
                A2aOutbound.EXCHANGE_HEADER, exchangeId.toString()));
    }

    private EvidenceEntry record(String agentName, MandateClaims claims, String method, ExchangeRequest request,
                                 Decision decision, Outcome outcome, String reason, String responseHash,
                                 Map<String, String> attributes) {
        return evidence.record(new EvidenceSubmission(clock.instant(), agentName, claims.principalId(), ACTION,
                agentName + "/" + method, decision, reason, claims.mandateId().toString(), request.requestHash(),
                responseHash, outcome, request.requestId().toString(), attributes));
    }

    private A2aResponse unauthenticated(JsonNode id, String message, List<String> problems) {
        return rpc.error(401, id, JsonRpc.DENIED, message, problems.isEmpty() ? null : Map.of("problems", problems),
                Map.of());
    }

    private LedgerProperties.A2a.Agent agent(String name) {
        LedgerProperties.A2a a2a = properties.a2a();
        LedgerProperties.A2a.Agent agent = a2a == null || a2a.agents() == null ? null : a2a.agents().get(name);
        if (agent == null || agent.url() == null) {
            throw LedgerException.notFound("A2A agent " + name);
        }
        return agent;
    }

    private Duration timeout() {
        LedgerProperties.A2a a2a = properties.a2a();
        return a2a == null || a2a.timeout() == null ? Duration.ofSeconds(60) : a2a.timeout();
    }

    private Duration maxAge() {
        LedgerProperties.A2a a2a = properties.a2a();
        return a2a == null || a2a.requestMaxAge() == null ? Duration.ofMinutes(5) : a2a.requestMaxAge();
    }
}
