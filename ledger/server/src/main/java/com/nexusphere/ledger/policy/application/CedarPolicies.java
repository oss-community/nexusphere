package com.nexusphere.ledger.policy.application;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.AuthorizationSuccessResponse;
import com.cedarpolicy.model.Effect;
import com.cedarpolicy.model.entity.Entities;
import com.cedarpolicy.model.exception.AuthException;
import com.cedarpolicy.model.policy.Policy;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.value.CedarMap;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimLong;
import com.cedarpolicy.value.PrimString;
import com.cedarpolicy.value.Value;
import com.nexusphere.ledger.chain.AgentModel;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class CedarPolicies {

    public static final String DEFAULT_PERMIT = "ledger-default-permit";
    public static final String PRINCIPAL = "principal";
    public static final String OPERATOR = "operator";
    public static final String ANY = "any";
    private static final Set<String> APPROVERS = Set.of(PRINCIPAL, OPERATOR, ANY);
    private static final BasicAuthorizationEngine ENGINE = new BasicAuthorizationEngine();
    private static final EntityTypeName AGENT = EntityTypeName.parse("Agent").orElseThrow();
    private static final EntityTypeName ACTION = EntityTypeName.parse("Action").orElseThrow();
    private static final EntityTypeName TARGET = EntityTypeName.parse("Target").orElseThrow();

    public record Request(String agentId, String principalId, String action, String target, String inputHash,
                          Map<String, String> attributes, AgentModel model, Instant at) {
    }

    public record Verdict(boolean allowed, List<String> policies, List<String> errors, String approver) {

        public Verdict(boolean allowed, List<String> policies, List<String> errors) {
            this(allowed, policies, errors, null);
        }
    }

    private final PolicySet set;
    private final Map<String, String> names;
    private final Map<String, String> approvals;
    private final List<String> ids;

    private CedarPolicies(PolicySet set, Map<String, String> names, Map<String, String> approvals, List<String> ids) {
        this.set = set;
        this.names = names;
        this.approvals = approvals;
        this.ids = ids;
    }

    public static CedarPolicies compile(String text, boolean defaultPermit) {
        PolicySet parsed;
        try {
            parsed = PolicySet.parsePolicies(text);
        } catch (AuthException | RuntimeException e) {
            throw new IllegalArgumentException("The policies cannot be parsed: " + e.getMessage(), e);
        }
        if (parsed.getNumTemplates() > 0) {
            throw new IllegalArgumentException("Policy templates are not supported");
        }
        Map<String, String> names = new HashMap<>();
        Map<String, String> approvals = new HashMap<>();
        Set<String> seen = new TreeSet<>();
        Set<Policy> policies = new HashSet<>(parsed.policies);
        for (Policy policy : parsed.policies) {
            String id = annotation(policy, "id");
            String name = id == null || id.isBlank() ? policy.getID() : id;
            if (!seen.add(name)) {
                throw new IllegalArgumentException("The policy id " + name + " is used twice");
            }
            names.put(policy.getID(), name);
            String approval = annotation(policy, "approval");
            if (approval != null) {
                if (!APPROVERS.contains(approval)) {
                    throw new IllegalArgumentException("The approval of policy " + name
                            + " must be principal, operator or any");
                }
                if (!forbids(policy)) {
                    throw new IllegalArgumentException("Only a forbid policy can ask for approval, not " + name);
                }
                approvals.put(name, approval);
            }
        }
        if (defaultPermit) {
            if (seen.contains(DEFAULT_PERMIT)) {
                throw new IllegalArgumentException("The policy id " + DEFAULT_PERMIT + " is reserved");
            }
            policies.add(new Policy("permit(principal, action, resource);", DEFAULT_PERMIT));
            names.put(DEFAULT_PERMIT, DEFAULT_PERMIT);
        }
        List<String> ids = new ArrayList<>(seen);
        return new CedarPolicies(new PolicySet(policies), Map.copyOf(names), Map.copyOf(approvals),
                List.copyOf(ids));
    }

    private static String annotation(Policy policy, String name) {
        try {
            return policy.getAnnotation(name);
        } catch (AuthException | RuntimeException e) {
            return null;
        }
    }

    private static boolean forbids(Policy policy) {
        try {
            return policy.effect() == Effect.FORBID;
        } catch (AuthException | RuntimeException e) {
            return false;
        }
    }

    private String approver(List<String> determining) {
        if (determining.isEmpty() || !determining.stream().allMatch(approvals::containsKey)) {
            return null;
        }
        Set<String> wanted = new TreeSet<>(determining.stream().map(approvals::get).toList());
        wanted.remove(ANY);
        if (wanted.size() > 1) {
            return null;
        }
        return wanted.isEmpty() ? ANY : wanted.iterator().next();
    }

    public List<String> ids() {
        return ids;
    }

    public Verdict evaluate(Request request) {
        Map<String, Value> context = new LinkedHashMap<>();
        context.put("principalId", new PrimString(request.principalId()));
        context.put("target", new PrimString(request.target() == null ? "" : request.target()));
        if (request.inputHash() != null) {
            context.put("inputHash", new PrimString(request.inputHash()));
        }
        Map<String, Value> attributes = new TreeMap<>();
        if (request.attributes() != null) {
            request.attributes().forEach((name, value) -> attributes.put(name, new PrimString(value)));
        }
        context.put("attributes", new CedarMap(attributes));
        if (request.model() != null) {
            Map<String, Value> model = new TreeMap<>();
            if (request.model().name() != null) {
                model.put("name", new PrimString(request.model().name()));
            }
            if (request.model().version() != null) {
                model.put("version", new PrimString(request.model().version()));
            }
            context.put("model", new CedarMap(model));
        }
        var time = request.at().atOffset(ZoneOffset.UTC);
        context.put("hour", new PrimLong(time.getHour()));
        context.put("weekday", new PrimLong(time.getDayOfWeek().getValue()));
        AuthorizationRequest authorization = new AuthorizationRequest(new EntityUID(AGENT, request.agentId()),
                new EntityUID(ACTION, request.action()),
                new EntityUID(TARGET, request.target() == null ? "" : request.target()), context);
        AuthorizationResponse response;
        try {
            response = ENGINE.isAuthorized(authorization, set, new Entities());
        } catch (AuthException e) {
            return new Verdict(false, List.of(), List.of("The policies cannot be evaluated: " + e.getMessage()));
        }
        if (response.success.isEmpty()) {
            return new Verdict(false, List.of(), List.of("The policies cannot be evaluated: "
                    + response));
        }
        AuthorizationSuccessResponse success = response.success.get();
        List<String> errors = success.getErrors().stream()
                .map(e -> names.getOrDefault(e.getPolicyId(), e.getPolicyId()) + ": " + e.getError()).toList();
        List<String> determining = success.getReason().stream().map(id -> names.getOrDefault(id, id)).sorted()
                .toList();
        boolean allowed = success.isAllowed() && errors.isEmpty();
        return new Verdict(allowed, determining, errors, allowed || !errors.isEmpty() ? null : approver(determining));
    }
}
