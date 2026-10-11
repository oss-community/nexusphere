package com.nexusphere.ledger.anomaly.api.rest;

import com.nexusphere.ledger.anomaly.application.Anomaly;
import com.nexusphere.ledger.anomaly.application.AnomalyService;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/anomalies")
class AnomalyController {

    record AnomalyResponse(UUID id, String agentId, String kind, String status, double observed, double expected,
                           double score, Instant windowStart, Instant windowEnd, Instant detectedAt,
                           Instant resolvedAt, UUID evidenceId, UUID resolvedEvidenceId) {

        static AnomalyResponse of(Anomaly a) {
            return new AnomalyResponse(a.id(), a.agentId(), a.kind().name(), a.status().name(), a.observed(),
                    a.expected(), a.score(), a.windowStart(), a.windowEnd(), a.detectedAt(), a.resolvedAt(),
                    a.evidenceId(), a.resolvedEvidenceId());
        }
    }

    record AnomalyPage(List<AnomalyResponse> items) {
    }

    private final AnomalyService anomalies;

    AnomalyController(AnomalyService anomalies) {
        this.anomalies = anomalies;
    }

    @GetMapping
    AnomalyPage list(Caller caller, @RequestParam(required = false) String status,
                     @RequestParam(required = false) String agentId,
                     @RequestParam(defaultValue = "100") int limit) {
        caller.requireOperator();
        return new AnomalyPage(anomalies.list(status(status), agentId, limit).stream().map(AnomalyResponse::of)
                .toList());
    }

    @GetMapping("/{id}")
    AnomalyResponse get(Caller caller, @PathVariable UUID id) {
        caller.requireOperator();
        return anomalies.find(id).map(AnomalyResponse::of).orElseThrow(() -> LedgerException.notFound("Anomaly " + id));
    }

    @PostMapping("/detect")
    AnomalyPage detect(Caller caller) {
        caller.requireOperator();
        return new AnomalyPage(anomalies.detect().stream().map(AnomalyResponse::of).toList());
    }

    private static Anomaly.Status status(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Anomaly.Status.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw LedgerException.invalid("The query has invalid parameters.",
                    Map.of("status", "must be one of OPEN, RESOLVED"));
        }
    }
}
