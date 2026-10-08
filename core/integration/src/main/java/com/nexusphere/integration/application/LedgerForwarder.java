package com.nexusphere.integration.application;

import com.nexusphere.integration.domain.model.LedgerGrant;
import com.nexusphere.integration.domain.model.OutboxMessage;
import com.nexusphere.integration.domain.repository.LedgerOutbox;
import com.nexusphere.shared.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class LedgerForwarder {

    static final Duration MAX_GRANT_LIFETIME = Duration.ofDays(365);
    private static final Set<Integer> RETRIABLE = Set.of(0, 401, 403, 408, 425, 429);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final Logger log = LoggerFactory.getLogger(LedgerForwarder.class);

    private final LedgerProperties properties;
    private final LedgerOutbox outbox;
    private final LedgerGateway ledger;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final TimeProvider time;

    LedgerForwarder(LedgerProperties properties, LedgerOutbox outbox, LedgerGateway ledger,
                    TransactionTemplate transactions, JsonMapper json, TimeProvider time) {
        this.properties = properties;
        this.outbox = outbox;
        this.ledger = ledger;
        this.transactions = transactions;
        this.json = json;
        this.time = time;
    }

    @Scheduled(initialDelayString = "${nexusphere.ledger.forward-interval:5s}",
            fixedDelayString = "${nexusphere.ledger.forward-interval:5s}")
    public void scheduled() {
        try {
            forward();
        } catch (RuntimeException e) {
            log.warn("Forwarding to the ledger failed: {}", e.getMessage());
        }
    }

    public int forward() {
        if (!properties.enabled()) {
            return 0;
        }
        Integer sent = transactions.execute(status -> outbox.lock() ? deliverBatch() : 0);
        return sent == null ? 0 : sent;
    }

    private int deliverBatch() {
        int sent = 0;
        for (OutboxMessage message : outbox.pending(properties.batch())) {
            try {
                deliver(message);
                outbox.markSent(message.id(), time.now());
                sent++;
            } catch (LedgerGateway.Unavailable e) {
                if (retriable(e.status())) {
                    log.warn("The ledger did not accept outbox message {}: {}", message.id(), e.getMessage());
                    outbox.markFailed(message.id(), e.getMessage());
                    break;
                }
                log.error("The ledger rejected outbox message {}: {}", message.id(), e.getMessage());
                outbox.markRejected(message.id(), e.getMessage(), time.now());
            } catch (RuntimeException e) {
                log.error("Outbox message {} could not be delivered", message.id(), e);
                outbox.markFailed(message.id(), String.valueOf(e.getMessage()));
                break;
            }
        }
        return sent;
    }

    private void deliver(OutboxMessage message) {
        Map<String, Object> payload = json.readValue(message.payload(), MAP);
        switch (message.kind()) {
            case EVIDENCE -> ledger.recordEvidence(payload);
            case GRANT -> grant(payload);
            case REVOKE -> revoke(payload);
        }
    }

    private void grant(Map<String, Object> payload) {
        UUID delegationId = UUID.fromString((String) payload.get("delegationId"));
        if (outbox.activeGrant(delegationId).isPresent()) {
            return;
        }
        Instant now = time.now();
        Instant validFrom = Instant.parse((String) payload.get("validFrom"));
        Instant cap = now.plus(MAX_GRANT_LIFETIME);
        Instant expiresAt = payload.get("validUntil") == null ? cap
                : min(Instant.parse((String) payload.get("validUntil")), cap);
        if (!expiresAt.isAfter(now)) {
            return;
        }
        String agentId = (String) payload.get("agentId");
        ledger.ensureAgent(agentId, "Nexusphere principal " + agentId, "network:" + payload.get("networkId"));
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("principalId", payload.get("principalId"));
        grant.put("agentId", agentId);
        grant.put("actions", payload.get("actions"));
        grant.put("targets", payload.get("targets"));
        if (validFrom.isAfter(now)) {
            grant.put("notBefore", validFrom.toString());
        }
        grant.put("expiresAt", expiresAt.toString());
        grant.put("reason", "Nexusphere delegation " + delegationId);
        String grantId = ledger.createGrant(grant);
        outbox.saveGrant(new LedgerGrant(delegationId, grantId, agentId, now, null));
    }

    private void revoke(Map<String, Object> payload) {
        UUID delegationId = UUID.fromString((String) payload.get("delegationId"));
        outbox.activeGrant(delegationId).ifPresent(grant -> {
            ledger.revokeGrant(grant.grantId(), (String) payload.get("reason"));
            outbox.markGrantRevoked(grant.grantId(), time.now());
        });
    }

    private static boolean retriable(int status) {
        return status >= 500 || RETRIABLE.contains(status);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
