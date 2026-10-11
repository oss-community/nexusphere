package com.nexusphere.ledger.approval.application;

import com.nexusphere.ledger.server.cluster.Leadership;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.security.PrincipalIdentity;
import com.nexusphere.ledger.server.security.PrincipalTokens;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.server.web.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
class ApprovalNotifier {

    static final String CIBA_GRANT = "urn:openid:params:grant-type:ciba";
    private static final Logger log = LoggerFactory.getLogger(ApprovalNotifier.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ApprovalService approvals;
    private final Leadership leadership;
    private final LedgerSigner signer;
    private final PrincipalTokens tokens;
    private final JsonMapper json;
    private final Clock clock;
    private final URI webhook;
    private final LedgerProperties.Approval.Ciba ciba;

    ApprovalNotifier(ApprovalService approvals, LedgerSigner signer, PrincipalTokens tokens, JsonMapper json,
                     LedgerProperties properties, Leadership leadership, Clock clock) {
        this.approvals = approvals;
        this.leadership = leadership;
        this.signer = signer;
        this.tokens = tokens;
        this.json = json;
        this.clock = clock;
        LedgerProperties.Approval approval = properties.approval();
        this.webhook = approval == null || approval.webhookUrl() == null
                || approval.webhookUrl().toString().isBlank() ? null : approval.webhookUrl();
        this.ciba = approval == null || approval.ciba() == null || !approval.ciba().enabled() ? null
                : approval.ciba();
        if (ciba != null) {
            if (!tokens.enabled()) {
                throw new IllegalStateException("ledger.approval.ciba needs ledger.oidc.issuer to check its tokens");
            }
            if (ciba.tokenEndpoint() == null || ciba.tokenEndpoint().toString().isBlank()
                    || ciba.clientId() == null || ciba.clientId().isBlank()) {
                throw new IllegalStateException(
                        "ledger.approval.ciba needs token-endpoint and client-id with its endpoint");
            }
        }
    }

    @Scheduled(fixedDelayString = "${ledger.approval.poll-interval}",
            initialDelayString = "${ledger.approval.poll-interval}")
    void tick() {
        if (!leadership.isLeader()) {
            return;
        }
        try {
            approvals.expireDue();
            if (webhook != null) {
                approvals.unnotified().forEach(this::notify);
            }
            if (ciba != null) {
                approvals.cibaUnstarted().forEach(this::start);
                approvals.cibaDue().forEach(this::poll);
            }
        } catch (RuntimeException e) {
            log.warn("Approval notifications failed: {}", e.getMessage());
        }
    }

    private void notify(Approval approval) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "approval.requested");
        body.put("approvalId", approval.id().toString());
        body.put("agentId", approval.agentId());
        body.put("principalId", approval.principalId());
        body.put("action", approval.action());
        body.put("target", approval.target());
        body.put("inputHash", approval.inputHash());
        body.put("policyHash", approval.policyHash());
        body.put("policies", approval.policies());
        body.put("approver", approval.approver());
        body.put("requestedAt", approval.requestedAt().toString());
        body.put("expiresAt", approval.expiresAt().toString());
        byte[] bytes = json.writeValueAsBytes(body);
        HttpRequest.Builder request = HttpRequest.newBuilder(webhook).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        signer.signHttp("POST", webhook, bytes, clock.instant()).forEach(request::header);
        try {
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                approvals.notified(approval.id());
            } else {
                log.warn("The approval webhook answered {} for approval {}", response.statusCode(), approval.id());
            }
        } catch (IOException e) {
            log.warn("The approval webhook cannot be reached for approval {}: {}", approval.id(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void start(Approval approval) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("scope", ciba.scope() == null || ciba.scope().isBlank() ? "openid" : ciba.scope());
        form.put("login_hint", approval.principalId());
        form.put("binding_message", binding(approval));
        Optional<JsonNode> answer = post(ciba.endpoint(), form, approval);
        if (answer.isEmpty()) {
            approvals.cibaStopped(approval.id());
            return;
        }
        String requestId = answer.get().path("auth_req_id").asString(null);
        if (requestId == null) {
            log.warn("The CIBA endpoint gave no auth_req_id for approval {}", approval.id());
            approvals.cibaStopped(approval.id());
            return;
        }
        approvals.ciba(approval.id(), requestId, clock.instant().plusSeconds(interval(answer.get(), 5)));
    }

    private void poll(ApprovalService.CibaPoll due) {
        Approval approval = due.approval();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", CIBA_GRANT);
        form.put("auth_req_id", due.requestId());
        HttpResponse<String> response;
        try {
            response = send(ciba.tokenEndpoint(), form);
        } catch (IOException e) {
            log.warn("The CIBA token endpoint cannot be reached for approval {}: {}", approval.id(), e.getMessage());
            approvals.ciba(approval.id(), due.requestId(), clock.instant().plusSeconds(5));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        JsonNode body = json.readTree(response.body());
        if (response.statusCode() == 200) {
            Optional<PrincipalIdentity> identity = identity(body);
            ApprovalService.Resolver resolver = identity.filter(p -> p.principalId().equals(approval.principalId()))
                    .map(p -> new ApprovalService.Resolver("ciba", p.subject(), approval.principalId(),
                            Consents.of(p)))
                    .orElse(null);
            if (resolver == null) {
                log.warn("The CIBA tokens for approval {} do not name its principal", approval.id());
                approvals.cibaStopped(approval.id());
                return;
            }
            answer(() -> approvals.approve(approval.id(), resolver, null));
            return;
        }
        String error = body.path("error").asString("");
        switch (error) {
            case "authorization_pending" -> approvals.ciba(approval.id(), due.requestId(),
                    clock.instant().plusSeconds(5));
            case "slow_down" -> approvals.ciba(approval.id(), due.requestId(), clock.instant().plusSeconds(10));
            case "access_denied" -> answer(() -> approvals.deny(approval.id(), new ApprovalService.Resolver("ciba",
                    approval.principalId(), approval.principalId(), Map.of()),
                    "The principal denied the request on their device."));
            default -> {
                log.warn("CIBA for approval {} ended with {}", approval.id(), error.isEmpty()
                        ? response.statusCode() : error);
                approvals.cibaStopped(approval.id());
            }
        }
    }

    private void answer(Runnable resolution) {
        try {
            resolution.run();
        } catch (LedgerException e) {
            log.debug("A CIBA answer came too late: {}", e.getMessage());
        }
    }

    private Optional<PrincipalIdentity> identity(JsonNode body) {
        for (String field : new String[]{"id_token", "access_token"}) {
            String token = body.path(field).asString(null);
            if (token != null) {
                Optional<PrincipalIdentity> identity = tokens.authenticate(token);
                if (identity.isPresent()) {
                    return identity;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<JsonNode> post(URI uri, Map<String, String> form, Approval approval) {
        try {
            HttpResponse<String> response = send(uri, form);
            if (response.statusCode() / 100 != 2) {
                log.warn("The CIBA endpoint answered {} for approval {}: {}", response.statusCode(), approval.id(),
                        response.body());
                return Optional.empty();
            }
            return Optional.of(json.readTree(response.body()));
        } catch (IOException e) {
            log.warn("The CIBA endpoint cannot be reached for approval {}: {}", approval.id(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private HttpResponse<String> send(URI uri, Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        String credentials = encode(ciba.clientId()) + ":" + encode(ciba.clientSecret() == null ? ""
                : ciba.clientSecret());
        return http.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String binding(Approval approval) {
        String text = (approval.agentId() + " " + approval.describe()).replaceAll("[^A-Za-z0-9 ._/:-]", " ");
        return text.length() > 64 ? text.substring(0, 64) : text;
    }

    private static long interval(JsonNode answer, long fallback) {
        long interval = answer.path("interval").asLong(fallback);
        return interval < 1 ? fallback : interval;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
