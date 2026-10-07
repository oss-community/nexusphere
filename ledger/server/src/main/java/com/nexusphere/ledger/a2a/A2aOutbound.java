package com.nexusphere.ledger.a2a;

import com.nexusphere.ledger.authorization.application.DecisionService;
import com.nexusphere.ledger.authorization.application.MandateService;
import com.nexusphere.ledger.authorization.domain.model.DecisionRequest;
import com.nexusphere.ledger.authorization.domain.model.DecisionResult;
import com.nexusphere.ledger.authorization.domain.model.Mandate;
import com.nexusphere.ledger.authorization.domain.model.OutcomeReport;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.evidence.domain.model.Decision;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.mandate.ExchangeReceipt;
import com.nexusphere.ledger.mandate.ExchangeRequest;
import com.nexusphere.ledger.mandate.Jws;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.security.Caller;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    private static final Logger log = LoggerFactory.getLogger(A2aOutbound.class);

    private final LedgerProperties properties;
    private final DecisionService decisions;
    private final MandateService mandates;
    private final LedgerSigner signer;
    private final A2aKeys keys;
    private final A2aHttp http;
    private final A2aExchanges exchanges;
    private final JsonRpc rpc;
    private final Clock clock;

    A2aOutbound(LedgerProperties properties, DecisionService decisions, MandateService mandates, LedgerSigner signer,
                A2aKeys keys, A2aHttp http, A2aExchanges exchanges, JsonRpc rpc, Clock clock) {
        this.properties = properties;
        this.decisions = decisions;
        this.mandates = mandates;
        this.signer = signer;
        this.keys = keys;
        this.http = http;
        this.exchanges = exchanges;
        this.rpc = rpc;
        this.clock = clock;
    }

    A2aResponse send(Caller caller, String peerName, String principalId, byte[] body) {
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
        Mandate mandate = mandates.forExchange(decision.decision().grantId(), peer.issuer());
        String requestToken = signer.sign(ExchangeRequest.TYPE, new ExchangeRequest(mandates.issuer(),
                caller.agentId(), peer.issuer(), decisionId, mandate.id(), method, requestHash, now).toPayload());
        UUID exchangeId = UUID.randomUUID();
        exchanges.reserve(new A2aExchange(exchangeId, A2aExchange.OUTBOUND, peerName, decisionId, caller.agentId(),
                principalId, method, mandate.id(), mandate.token(), requestToken, requestHash, null, null, null, null,
                null, null, now));
        Map<String, String> headers = new HashMap<>();
        headers.put(DECISION_HEADER, decisionId.toString());
        headers.put(EXCHANGE_HEADER, exchangeId.toString());
        A2aHttp.Reply reply;
        try {
            reply = http.post(peer.url(), timeout(), Map.of(MANDATE_HEADER, mandate.token(),
                    REQUEST_HEADER, requestToken), body, RECEIPT_HEADER);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("A2A peer {} is unavailable: {}", peer.url(), e.getMessage());
            A2aResponse failed = rpc.error(502, id, JsonRpc.UPSTREAM_UNAVAILABLE, "The A2A peer is unavailable.",
                    null, headers);
            decisions.reportOutcome(decisionId, caller.agentId(), new OutcomeReport(Outcome.FAILED,
                    Hashes.sha256(failed.body()), "The A2A peer " + peerName + " is unavailable.",
                    Map.of("a2a.receipt", "MISSING")));
            exchanges.complete(exchangeId, Hashes.sha256(failed.body()), 502, Outcome.FAILED.name(), null, null,
                    "MISSING");
            return failed;
        }
        String responseHash = Hashes.sha256(reply.body());
        String receipt = reply.headers().get(RECEIPT_HEADER);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("a2a.status", String.valueOf(reply.status()));
        attributes.put("a2a.mandate", mandate.id().toString());
        String receiptStatus = checkReceipt(receipt, peer, decisionId, requestHash, responseHash, attributes);
        attributes.put("a2a.receipt", receiptStatus);
        boolean failed = rpc.failed(reply.status(), reply.body());
        Outcome outcome = failed ? Outcome.FAILED : Outcome.SUCCEEDED;
        decisions.reportOutcome(decisionId, caller.agentId(), new OutcomeReport(outcome, responseHash,
                failed ? "The A2A peer answered with HTTP " + reply.status() + " or a JSON-RPC error." : null,
                attributes));
        exchanges.complete(exchangeId, responseHash, reply.status(), outcome.name(), null, receipt, receiptStatus);
        if (receipt != null) {
            headers.put(RECEIPT_HEADER, receipt);
        }
        headers.put(RECEIPT_STATUS_HEADER, receiptStatus);
        return new A2aResponse(reply.status(), reply.body(), headers);
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
