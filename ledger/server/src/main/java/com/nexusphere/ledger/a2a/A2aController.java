package com.nexusphere.ledger.a2a;

import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.LedgerException;
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

import java.time.Instant;
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

    private final A2aOutbound outbound;
    private final A2aInbound inbound;
    private final A2aExchanges exchanges;

    A2aController(A2aOutbound outbound, A2aInbound inbound, A2aExchanges exchanges) {
        this.outbound = outbound;
        this.inbound = inbound;
        this.exchanges = exchanges;
    }

    @PostMapping("/a2a/out/{peer}")
    ResponseEntity<StreamingResponseBody> send(
            Caller caller, @PathVariable String peer,
            @RequestHeader(name = PRINCIPAL_HEADER, required = false) String principalId,
            @RequestHeader(name = A2aOutbound.MANDATE_HEADER, required = false) String mandate,
            @RequestBody byte[] body) {
        return respond(outbound.send(caller, peer, principalId, mandate, body));
    }

    @PostMapping("/a2a/in/{agent}")
    ResponseEntity<StreamingResponseBody> receive(
            @PathVariable String agent,
            @RequestHeader(name = A2aOutbound.MANDATE_HEADER, required = false) String mandate,
            @RequestHeader(name = A2aOutbound.REQUEST_HEADER, required = false) String request,
            @RequestBody byte[] body) {
        return respond(inbound.receive(agent, mandate, request, body));
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
