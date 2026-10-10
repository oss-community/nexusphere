package com.nexusphere.ledger.a2a;

import com.nexusphere.ledger.agent.application.AgentService;
import com.nexusphere.ledger.agent.domain.model.Agent;
import com.nexusphere.ledger.authorization.application.DecisionService;
import com.nexusphere.ledger.authorization.application.MandateService;
import com.nexusphere.ledger.authorization.domain.model.DecisionRequest;
import com.nexusphere.ledger.authorization.domain.model.DecisionResult;
import com.nexusphere.ledger.authorization.domain.model.Mandate;
import com.nexusphere.ledger.authorization.domain.model.OutcomeReport;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.evidence.domain.model.Decision;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.mandate.ExchangeReceipt;
import com.nexusphere.ledger.mandate.ExchangeRequest;
import com.nexusphere.ledger.mandate.Jws;
import com.nexusphere.ledger.mandate.MandateCheck;
import com.nexusphere.ledger.mandate.MandateClaims;
import com.nexusphere.ledger.mandate.MandateProblem;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.server.web.EventStream;
import com.nexusphere.ledger.server.web.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class A2aOutbound {

    static final String ACTION = "a2a/send";
    static final String MANDATE_HEADER = "X-Nexusphere-Mandate";
    static final String REQUEST_HEADER = "X-Nexusphere-Request";
    static final String RECEIPT_HEADER = "X-Nexusphere-Receipt";
    static final String DECISION_HEADER = "X-Ledger-Decision";
    static final String EXCHANGE_HEADER = "X-Ledger-Exchange";
    static final String RECEIPT_STATUS_HEADER = "X-Ledger-Receipt";
    static final String WRONG_AGENT = "WRONG_AGENT";
    static final String WRONG_PRINCIPAL = "WRONG_PRINCIPAL";
    static final String UNDISCLOSED = "UNDISCLOSED";
    static final String STALE_KEY = "STALE_KEY";
    static final String GRANT_MISMATCH = "GRANT_MISMATCH";

    private static final Logger log = LoggerFactory.getLogger(A2aOutbound.class);

    private final LedgerProperties properties;
    private final DecisionService decisions;
    private final MandateService mandates;
    private final AgentService agents;
    private final LedgerSigner signer;
    private final A2aKeys keys;
    private final A2aHttp http;
    private final A2aExchanges exchanges;
    private final JsonRpc rpc;
    private final Clock clock;

    A2aOutbound(LedgerProperties properties, DecisionService decisions, MandateService mandates, AgentService agents,
                LedgerSigner signer, A2aKeys keys, A2aHttp http, A2aExchanges exchanges, JsonRpc rpc, Clock clock) {
        this.properties = properties;
        this.decisions = decisions;
        this.mandates = mandates;
        this.agents = agents;
        this.signer = signer;
        this.keys = keys;
        this.http = http;
        this.exchanges = exchanges;
        this.rpc = rpc;
        this.clock = clock;
    }

    A2aResponse send(Caller caller, String peerName, String principalId, String presented, byte[] body) {
        LedgerProperties.A2a.Peer peer = peer(peerName);
        A2aResponse invalid = rpc.invalid(body);
        if (invalid != null) {
            return invalid;
        }
        JsonNode message = rpc.read(body).orElseThrow();
        JsonNode id = message.get("id");
        String method = message.path("method").asString();
        if (caller.isOperator()) {
            return rpc.error(403, id, JsonRpc.DENIED, "Only agents may send A2A messages through the gateway.", null,
                    Map.of());
        }
        if (principalId == null || principalId.isBlank()) {
            return rpc.error(400, id, JsonRpc.INVALID_PARAMS, "An A2A request needs the X-Ledger-Principal header.",
                    null, Map.of());
        }
        String requestHash = Hashes.sha256(body);
        Agent agent = agents.get(caller.agentId());
        MandateClaims bound = null;
        if (agent.signingKey() != null) {
            if (presented == null || presented.isBlank()) {
                return rpc.error(400, id, JsonRpc.INVALID_PARAMS, "Agent " + agent.agentId()
                        + " has a signing key, so it must send its key-bound mandate in the " + MANDATE_HEADER
                        + " header.", null, Map.of());
            }
            MandateCheck check = keys.own().verifyBound(presented, requestHash);
            List<String> problems = new ArrayList<>(check.problems().stream().map(p -> p.code().name()).toList());
            bound = check.claims();
            if (bound != null) {
                problems.addAll(presentationProblems(bound, agent, principalId, peer));
            }
            if (bound == null || !problems.isEmpty()) {
                return rpc.error(403, id, JsonRpc.DENIED, "The presented mandate does not allow this request.",
                        Map.of("problems", problems), Map.of());
            }
        }
        DecisionResult decision;
        try {
            decision = decisions.decide(new DecisionRequest(caller.agentId(), principalId, ACTION,
                    peerName + "/" + method, requestHash, null, Map.of("a2a.peer", peerName, "a2a.method", method)));
        } catch (LedgerException e) {
            return rpc.error(400, id, JsonRpc.INVALID_PARAMS, e.getMessage(), e.details(), Map.of());
        }
        UUID decisionId = decision.decision().id();
        if (decision.decision().decision() == Decision.DENY) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("decisionId", decisionId.toString());
            data.put("reasonCode", decision.decision().reasonCode().name());
            return rpc.error(200, id, JsonRpc.DENIED, "Denied by Nexusphere Ledger: " + decision.reason(), data,
                    Map.of(DECISION_HEADER, decisionId.toString()));
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        if (bound != null && !bound.grantId().equals(decision.decision().grantId())) {
            A2aResponse refused = rpc.error(403, id, JsonRpc.DENIED, "The presented mandate is for grant "
                    + bound.grantId() + ", but the decision used grant " + decision.decision().grantId() + ".",
                    Map.of("decisionId", decisionId.toString(), "problems", List.of(GRANT_MISMATCH)),
                    Map.of(DECISION_HEADER, decisionId.toString()));
            decisions.reportUndelivered(decisionId, caller.agentId(), new OutcomeReport(Outcome.FAILED,
                    Hashes.sha256(refused.body()), "The presented mandate is for another grant.",
                    Map.of("a2a.mandate", bound.mandateId().toString())));
            return refused;
        }
        UUID mandateId;
        String mandateToken;
        if (bound != null) {
            mandateId = bound.mandateId();
            mandateToken = presented;
        } else {
            Mandate mandate = mandates.forExchange(decision.decision().grantId(), peer.issuer());
            mandateId = mandate.id();
            mandateToken = mandate.token();
        }
        String requestToken = signer.sign(ExchangeRequest.TYPE, new ExchangeRequest(mandates.issuer(),
                caller.agentId(), peer.issuer(), decisionId, mandateId, method, requestHash, now).toPayload());
        UUID exchangeId = UUID.randomUUID();
        exchanges.reserve(new A2aExchange(exchangeId, A2aExchange.OUTBOUND, peerName, decisionId, caller.agentId(),
                principalId, method, mandateId, mandateToken, requestToken, requestHash, null, null, null, null,
                null, null, now));
        Map<String, String> headers = new HashMap<>();
        headers.put(DECISION_HEADER, decisionId.toString());
        headers.put(EXCHANGE_HEADER, exchangeId.toString());
        A2aHttp.Reply reply;
        try {
            reply = http.post(peer.url(), timeout(), Map.of(MANDATE_HEADER, mandateToken,
                    REQUEST_HEADER, requestToken), body, RECEIPT_HEADER);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("A2A peer {} is unavailable: {}", peer.url(), e.getMessage());
            A2aResponse failed = rpc.error(502, id, JsonRpc.UPSTREAM_UNAVAILABLE, "The A2A peer is unavailable.",
                    null, headers);
            decisions.reportUndelivered(decisionId, caller.agentId(), new OutcomeReport(Outcome.FAILED,
                    Hashes.sha256(failed.body()), "The A2A peer " + peerName + " is unavailable.",
                    Map.of("a2a.receipt", "MISSING")));
            exchanges.complete(exchangeId, Hashes.sha256(failed.body()), 502, Outcome.FAILED.name(), null, null,
                    "MISSING");
            return failed;
        }
        if (reply.streamed()) {
            Exchange exchange = new Exchange(caller.agentId(), peerName, peer, decisionId, exchangeId, mandateId,
                    requestHash);
            A2aHttp.Reply streamed = reply;
            return new A2aResponse(streamed.status(), null, headers, out -> relay(streamed, exchange, out));
        }
        String responseHash = Hashes.sha256(reply.body());
        String receipt = reply.headers().get(RECEIPT_HEADER);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a2a.status", String.valueOf(reply.status()));
        attributes.put("a2a.mandate", mandateId.toString());
        if (bound != null) {
            attributes.put("a2a.keyBinding", agent.signingKeyId());
        }
        String receiptStatus = checkReceipt(receipt, peer, decisionId, requestHash, responseHash, attributes);
        attributes.put("a2a.receipt", receiptStatus);
        boolean failed = rpc.failed(reply.status(), reply.body());
        Outcome outcome = failed ? Outcome.FAILED : Outcome.SUCCEEDED;
        OutcomeReport report = new OutcomeReport(outcome, responseHash,
                failed ? "The A2A peer answered with HTTP " + reply.status() + " or a JSON-RPC error." : null,
                attributes);
        if (receipt == null && reply.status() >= 400) {
            decisions.reportUndelivered(decisionId, caller.agentId(), report);
        } else {
            decisions.reportOutcome(decisionId, caller.agentId(), report);
        }
        exchanges.complete(exchangeId, responseHash, reply.status(), outcome.name(), null, receipt, receiptStatus);
        if (receipt != null) {
            headers.put(RECEIPT_HEADER, receipt);
        }
        headers.put(RECEIPT_STATUS_HEADER, receiptStatus);
        return new A2aResponse(reply.status(), reply.body(), headers);
    }

    private static List<String> presentationProblems(MandateClaims claims, Agent agent, String principalId,
                                                     LedgerProperties.A2a.Peer peer) {
        List<String> problems = new ArrayList<>();
        if (!agent.agentId().equals(claims.agentId())) {
            problems.add(WRONG_AGENT);
        }
        if (!peer.issuer().equals(claims.audience())) {
            problems.add(MandateProblem.WRONG_AUDIENCE.name());
        }
        if (!claims.coversAction(ACTION)) {
            problems.add(MandateProblem.NOT_COVERED.name());
        }
        if (claims.grantId() == null || claims.principalId() == null) {
            problems.add(UNDISCLOSED);
        } else if (!claims.principalId().equals(principalId)) {
            problems.add(WRONG_PRINCIPAL);
        }
        if (claims.bound() && !SigningKeys.encode(claims.holderKey()).equals(agent.signingKey())) {
            problems.add(STALE_KEY);
        }
        return problems;
    }

    private record Exchange(String agentId, String peerName, LedgerProperties.A2a.Peer peer, UUID decisionId,
                            UUID exchangeId, UUID mandateId, String requestHash) {
    }

    private void relay(A2aHttp.Reply reply, Exchange exchange, OutputStream out) throws IOException {
        StreamDigest digest = new StreamDigest(rpc);
        String[] receipt = new String[1];
        IOException broken = null;
        try (InputStream stream = reply.stream()) {
            EventStream.relay(stream, out, event -> {
                if (StreamDigest.RECEIPT_EVENT.equals(event.name())) {
                    receipt[0] = event.data();
                    return false;
                }
                digest.accept(event.data());
                return true;
            });
        } catch (IOException e) {
            broken = e;
        }
        String responseHash = digest.hash();
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a2a.status", String.valueOf(reply.status()));
        attributes.put("a2a.mandate", exchange.mandateId().toString());
        String receiptStatus = checkReceipt(receipt[0], exchange.peer(), exchange.decisionId(),
                exchange.requestHash(), responseHash, attributes);
        attributes.put("a2a.receipt", receiptStatus);
        attributes.put("a2a.events", String.valueOf(digest.events()));
        attributes.put("a2a.stream", broken == null ? "COMPLETE" : "BROKEN");
        boolean failed = broken != null || digest.failed(reply.status());
        Outcome outcome = failed ? Outcome.FAILED : Outcome.SUCCEEDED;
        decisions.reportOutcome(exchange.decisionId(), exchange.agentId(), new OutcomeReport(outcome, responseHash,
                failed ? "The A2A stream from " + exchange.peerName()
                        + " broke or ended with an error or a failed task." : null, attributes));
        exchanges.complete(exchange.exchangeId(), responseHash, reply.status(), outcome.name(), null, receipt[0],
                receiptStatus);
        if (broken != null) {
            throw broken;
        }
    }

    private String checkReceipt(String token, LedgerProperties.A2a.Peer peer, UUID requestId, String requestHash,
                                String responseHash, Map<String, String> attributes) {
        if (token == null || token.isBlank()) {
            return "MISSING";
        }
        try {
            ExchangeReceipt receipt = ExchangeReceipt.fromPayload(
                    Jws.verified(token, ExchangeReceipt.TYPE, peer.issuer(), keys).payload());
            if (!receipt.audience().equals(mandates.issuer()) || !receipt.requestId().equals(requestId)
                    || !receipt.requestHash().equals(requestHash) || !receipt.responseHash().equals(responseHash)) {
                return "INVALID";
            }
            attributes.put("a2a.receipt.id", receipt.receiptId().toString());
            attributes.put("a2a.peer.sequence", String.valueOf(receipt.evidenceSequence()));
            attributes.put("a2a.peer.hash", receipt.evidenceHash());
            return "VERIFIED";
        } catch (RuntimeException e) {
            log.warn("The receipt from {} cannot be verified: {}", peer.issuer(), e.getMessage());
            return "INVALID";
        }
    }

    private LedgerProperties.A2a.Peer peer(String name) {
        LedgerProperties.A2a a2a = properties.a2a();
        LedgerProperties.A2a.Peer peer = a2a == null || a2a.peers() == null ? null : a2a.peers().get(name);
        if (peer == null || peer.url() == null || peer.issuer() == null || peer.issuer().isBlank()) {
            throw LedgerException.notFound("A2A peer " + name);
        }
        return peer;
    }

    private Duration timeout() {
        LedgerProperties.A2a a2a = properties.a2a();
        return a2a == null || a2a.timeout() == null ? Duration.ofSeconds(60) : a2a.timeout();
    }
}
