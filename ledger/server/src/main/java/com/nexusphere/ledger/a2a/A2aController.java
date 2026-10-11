package com.nexusphere.ledger.a2a;

import com.nexusphere.ledger.mandate.ExchangeStatement;
import com.nexusphere.ledger.mandate.Reconciliation;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.LedgerException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
class A2aController {

    static final String PRINCIPAL_HEADER = "X-Ledger-Principal";

    record ExchangeResponse(UUID id, String direction, String peer, UUID requestId, String agentId,
                            String principalId, String method, UUID mandateId, String mandate, String request,
                            String requestHash, String responseHash, Integer status, String outcome,
                            Long evidenceSequence, String receipt, String receiptStatus, Instant createdAt) {

        static ExchangeResponse of(A2aExchange e) {
            return new ExchangeResponse(e.id(), e.direction(), e.peer(), e.requestId(), e.agentId(), e.principalId(),
                    e.method(), e.mandateId(), e.mandateToken(), e.requestToken(), e.requestHash(), e.responseHash(),
                    e.status(), e.outcome(), e.evidenceSequence(), e.receipt(), e.receiptStatus(), e.createdAt());
        }
    }

    record StatementRequest(String peer, Instant from, Instant to) {
    }

    record StatementResponse(String statement, String issuer, String peer, Instant from, Instant to, int exchanges) {

        static StatementResponse of(A2aStatements.Signed signed) {
            ExchangeStatement s = signed.statement();
            return new StatementResponse(signed.token(), s.issuer(), s.peer(), s.from(), s.to(), s.exchanges().size());
        }
    }

    record ReconciliationRequest(String statement) {
    }

    record ReconciliationResponse(boolean reconciled, String ours, String theirs, Instant from, Instant to,
                                  int matched, int undelivered, List<Reconciliation.Discrepancy> discrepancies,
                                  String statement, long evidenceSequence) {
    }

    private final A2aOutbound outbound;
    private final A2aInbound inbound;
    private final A2aExchanges exchanges;
    private final A2aStatements statements;

    A2aController(A2aOutbound outbound, A2aInbound inbound, A2aExchanges exchanges, A2aStatements statements) {
        this.outbound = outbound;
        this.inbound = inbound;
        this.exchanges = exchanges;
        this.statements = statements;
    }

    @PostMapping("/api/v1/a2a/statements")
    StatementResponse statement(Caller caller, @RequestBody StatementRequest request) {
        caller.requireOperator();
        return StatementResponse.of(statements.statement(request.peer(), request.from(), request.to()));
    }

    @PostMapping("/api/v1/a2a/reconciliations")
    ReconciliationResponse reconcile(Caller caller, @RequestBody ReconciliationRequest request) {
        caller.requireOperator();
        A2aStatements.Reconciled done = statements.reconcile(request.statement());
        Reconciliation r = done.reconciliation();
        return new ReconciliationResponse(r.reconciled(), r.ours(), r.theirs(), r.from(), r.to(), r.matched(),
                r.undelivered(), r.discrepancies(), done.ours().token(), done.evidenceSequence());
    }

    @PostMapping("/a2a/out/{peer}")
    ResponseEntity<StreamingResponseBody> send(
            Caller caller, @PathVariable String peer,
            @RequestHeader(name = PRINCIPAL_HEADER, required = false) String principalId,
            @RequestHeader(name = A2aOutbound.MANDATE_HEADER, required = false) String mandate,
            @RequestHeader HttpHeaders headers, @RequestBody byte[] body) {
        return respond(outbound.send(caller, peer, principalId, mandate, headers, body));
    }

    @PostMapping("/a2a/in/{agent}")
    ResponseEntity<StreamingResponseBody> receive(
            @PathVariable String agent,
            @RequestHeader(name = A2aOutbound.MANDATE_HEADER, required = false) String mandate,
            @RequestHeader(name = A2aOutbound.REQUEST_HEADER, required = false) String request,
            @RequestBody byte[] body, HttpServletRequest http) {
        return respond(inbound.receive(agent, mandate, request, body, new A2aRequest(http.getMethod(),
                URI.create(http.getRequestURL().toString()), http::getHeader)));
    }

    @GetMapping("/api/v1/a2a/exchanges/{id}")
    ExchangeResponse exchange(Caller caller, @PathVariable UUID id) {
        return exchanges.find(id).filter(e -> caller.canActAs(e.agentId())).map(ExchangeResponse::of)
                .orElseThrow(() -> LedgerException.notFound("Exchange " + id));
    }

    private static ResponseEntity<StreamingResponseBody> respond(A2aResponse response) {
        HttpHeaders headers = new HttpHeaders();
        response.headers().forEach(headers::set);
        if (response.stream() != null) {
            headers.setContentType(MediaType.TEXT_EVENT_STREAM);
            headers.setCacheControl(CacheControl.noCache());
            return ResponseEntity.status(response.status()).headers(headers).body(response.stream());
        }
        byte[] body = response.body();
        if (body.length > 0) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return ResponseEntity.status(response.status()).headers(headers).body(out -> out.write(body));
    }
}
