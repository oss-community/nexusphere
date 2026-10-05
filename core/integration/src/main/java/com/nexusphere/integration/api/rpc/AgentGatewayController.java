package com.nexusphere.integration.api.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusphere.agreement.contract.AgreementCommands;
import com.nexusphere.agreement.contract.AgreementProposal;
import com.nexusphere.discovery.contract.CapabilityDiscovery;
import com.nexusphere.discovery.contract.CapabilityQuery;
import com.nexusphere.integration.api.rpc.AgentViews.AgentCard;
import com.nexusphere.integration.api.rpc.AgentViews.AgreementView;
import com.nexusphere.integration.api.rpc.AgentViews.CapabilityView;
import com.nexusphere.integration.api.rpc.AgentViews.PrincipalView;
import com.nexusphere.integration.api.rpc.AgentViews.TransactionView;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.transaction.contract.TransactionCommands;
import com.nexusphere.transaction.contract.TransactionRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/agent")
class AgentGatewayController {

    static final String PROTOCOL = "2.0";

    static final List<String> METHODS = List.of("capabilities/discover", "capabilities/get", "agreements/propose",
            "agreements/get", "transactions/request", "transactions/get");

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RpcError(int code, String message, Map<String, Object> data) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RpcResponse(String jsonrpc, JsonNode id, Object result, RpcError error) {

        static RpcResponse success(JsonNode id, Object result) {
            return new RpcResponse(PROTOCOL, id, result, null);
        }

        static RpcResponse failure(JsonNode id, int code, String message, Map<String, Object> data) {
            return new RpcResponse(PROTOCOL, id, null, new RpcError(code, message, data));
        }
    }

    private final CapabilityDiscovery discovery;
    private final AgreementCommands agreements;
    private final TransactionCommands transactions;
    private final JsonMapper json;

    AgentGatewayController(CapabilityDiscovery discovery, AgreementCommands agreements,
                           TransactionCommands transactions, JsonMapper json) {
        this.discovery = discovery;
        this.agreements = agreements;
        this.transactions = transactions;
        this.json = json;
    }

    @GetMapping("/card")
    AgentCard card(@PathVariable String networkId, PrincipalContext principal) {
        return new AgentCard("Nexusphere agent gateway", "JSON-RPC " + PROTOCOL,
                "/api/v1/networks/" + networkId + "/agent/rpc", METHODS, PrincipalView.of(principal));
    }

    @PostMapping(path = "/rpc", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object call(@PathVariable String networkId, @RequestBody String body, PrincipalContext principal,
                ExecutionContext context) {
        JsonNode payload;
        try {
            payload = json.readTree(body);
        } catch (JacksonException e) {
            return RpcResponse.failure(null, RpcFailure.PARSE_ERROR, "The request is not valid JSON", null);
        }
        if (payload.isArray()) {
            if (payload.isEmpty()) {
                return RpcResponse.failure(null, RpcFailure.INVALID_REQUEST, "The batch is empty", null);
            }
            return payload.valueStream().map(request -> handle(request, principal, context)).toList();
        }
        return handle(payload, principal, context);
    }

    private RpcResponse handle(JsonNode request, PrincipalContext principal, ExecutionContext context) {
        JsonNode id = request.isObject() ? request.get("id") : null;
        try {
            if (!request.isObject() || !PROTOCOL.equals(request.path("jsonrpc").asString(""))
                    || !request.path("method").isString()) {
                throw new RpcFailure(RpcFailure.INVALID_REQUEST, "A JSON-RPC 2.0 request with a method is required");
            }
            JsonNode params = request.path("params");
            if (!params.isMissingNode() && !params.isNull() && !params.isObject()) {
                throw new RpcFailure(RpcFailure.INVALID_PARAMS, "Parameters must be an object");
            }
            return RpcResponse.success(id, invoke(request.path("method").asString(),
                    new RpcParams(params, json), principal, context));
        } catch (RpcFailure e) {
            return RpcResponse.failure(id, e.code(), e.getMessage(), e.data());
        } catch (DomainException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("code", e.code());
            data.put("category", e.category().name());
            if (!e.details().isEmpty()) {
                data.put("details", e.details());
            }
            return RpcResponse.failure(id, code(e.category()), e.getMessage(), data);
        }
    }

    private Object invoke(String method, RpcParams params, PrincipalContext principal, ExecutionContext context) {
        return switch (method) {
            case "capabilities/discover" -> discovery.discover(principal, new CapabilityQuery(
                            params.text("typeCode"), params.text("ownerType"), params.organizationId("organizationId"),
                            params.networkId("originNetworkId"), params.text("scope")), context)
                    .stream().map(CapabilityView::of).toList();
            case "capabilities/get" -> {
                CapabilityId capabilityId = new CapabilityId(params.requiredUuid("capabilityId"));
                yield discovery.find(principal, capabilityId, context).map(CapabilityView::of)
                        .orElseThrow(() -> new NotFoundException("Capability", capabilityId));
            }
            case "agreements/propose" -> AgreementView.of(agreements.propose(principal, new AgreementProposal(
                    params.capabilityId("capabilityId"), params.text("type"), params.text("title"),
                    params.object("terms"), params.uuid("delegationId")), context));
            case "agreements/get" -> AgreementView.of(agreements.read(principal, params.requiredUuid("agreementId")));
            case "transactions/request" -> TransactionView.of(transactions.request(principal, new TransactionRequest(
                    params.requiredUuid("agreementId"), params.capabilityId("capabilityId"), params.text("type"),
                    params.object("metadata"), params.uuid("delegationId")), context));
            case "transactions/get" -> TransactionView.of(transactions.read(principal,
                    params.requiredUuid("transactionId")));
            default -> throw new RpcFailure(RpcFailure.METHOD_NOT_FOUND, "Method " + method + " is not supported",
                    Map.of("methods", METHODS));
        };
    }

    private static int code(ErrorCategory category) {
        return switch (category) {
            case VALIDATION_ERROR -> RpcFailure.INVALID_PARAMS;
            case AUTHENTICATION_ERROR -> -32001;
            case AUTHORIZATION_ERROR -> -32003;
            case NOT_FOUND -> -32004;
            case CONFLICT -> -32009;
            case INFRASTRUCTURE_ERROR -> RpcFailure.INTERNAL_ERROR;
            default -> -32022;
        };
    }
}
