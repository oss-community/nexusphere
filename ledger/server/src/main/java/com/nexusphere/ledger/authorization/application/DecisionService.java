package com.nexusphere.ledger.authorization.application;

import com.nexusphere.ledger.agent.domain.model.Agent;
import com.nexusphere.ledger.agent.domain.repository.AgentRepository;
import com.nexusphere.ledger.approval.application.Approval;
import com.nexusphere.ledger.approval.application.ApprovalService;
import com.nexusphere.ledger.authorization.domain.model.DecisionRecord;
import com.nexusphere.ledger.authorization.domain.model.DecisionRequest;
import com.nexusphere.ledger.authorization.domain.model.DecisionResult;
import com.nexusphere.ledger.authorization.domain.model.Grant;
import com.nexusphere.ledger.authorization.domain.model.GrantStatus;
import com.nexusphere.ledger.authorization.domain.model.OutcomeReport;
import com.nexusphere.ledger.authorization.domain.model.ReasonCode;
import com.nexusphere.ledger.authorization.domain.repository.DecisionRepository;
import com.nexusphere.ledger.authorization.domain.repository.GrantRepository;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.Timestamps;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.Decision;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.policy.application.CedarPolicies;
import com.nexusphere.ledger.policy.application.PolicyService;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class DecisionService {

    static final String DECISION_ATTRIBUTE = "decisionId";
    static final String USE_RETURNED_ATTRIBUTE = "grant.useReturned";

    private record Verdict(ReasonCode code, String reason, Grant grant) {

        Decision decision() {
            return code == ReasonCode.ALLOWED_BY_GRANT || code == ReasonCode.ALLOWED_BY_APPROVAL ? Decision.ALLOW
                    : Decision.DENY;
        }
    }

    private final GrantRepository grants;
    private final DecisionRepository decisions;
    private final AgentRepository agents;
    private final EvidenceService evidence;
    private final PolicyService policies;
    private final ApprovalService approvals;
    private final Clock clock;

    DecisionService(GrantRepository grants, DecisionRepository decisions, AgentRepository agents,
                    EvidenceService evidence, PolicyService policies, ApprovalService approvals, Clock clock) {
        this.grants = grants;
        this.decisions = decisions;
        this.agents = agents;
        this.evidence = evidence;
        this.policies = policies;
        this.approvals = approvals;
        this.clock = clock;
    }

    @Transactional
    public DecisionResult decide(DecisionRequest request) {
        Instant now = clock.instant();
        evidence.check(submission(request, Decision.ALLOW, Outcome.PENDING, null, null, now));
        if (request.attributes() != null
                && request.attributes().keySet().stream()
                .anyMatch(name -> name.startsWith("policy.") || name.startsWith("approval."))) {
            throw LedgerException.invalid("The decision request has invalid fields.",
                    Map.of("attributes", "names starting with policy. or approval. are set by the ledger"));
        }
        Optional<PolicyService.Evaluated> policy = policies.evaluate(new CedarPolicies.Request(request.agentId(),
                request.principalId(), request.action(), request.target(), request.inputHash(), request.attributes(),
                request.model(), now));
        Map<String, String> attributes = new TreeMap<>(request.attributes() == null ? Map.of() : request.attributes());
        policy.ifPresent(p -> {
            attributes.put(PolicyService.HASH_ATTRIBUTE, p.hash());
            attributes.put(PolicyService.EFFECT_ATTRIBUTE, policies.defaultEffect());
            if (!p.verdict().policies().isEmpty()) {
                attributes.put(PolicyService.IDS_ATTRIBUTE, String.join(",", p.verdict().policies()));
            }
        });
        evidence.check(submission(request.withAttributes(attributes), Decision.ALLOW, Outcome.PENDING, null, null,
                now));
        Verdict verdict = evaluate(request, now);
        Approval approval = null;
        if (verdict.decision() == Decision.ALLOW && policy.isPresent() && !policy.get().verdict().allowed()) {
            CedarPolicies.Verdict denied = policy.get().verdict();
            if (denied.approver() == null) {
                verdict = new Verdict(ReasonCode.POLICY_DENIED, denial(request, denied), null);
            } else {
                ApprovalService.Gate gate = approvals.gate(new ApprovalService.Request(request.agentId(),
                        request.principalId(), request.action(), request.target(), request.inputHash(),
                        policy.get().hash(), denied.policies(), denied.approver()), now);
                approval = gate.approval();
                attributes.put(ApprovalService.ID_ATTRIBUTE, approval.id().toString());
                verdict = approved(request, gate, verdict);
                if (gate.answer() == ApprovalService.Answer.PENDING) {
                    attributes.put(ApprovalService.EXPIRES_ATTRIBUTE, Timestamps.format(approval.expiresAt()));
                }
            }
        }
        DecisionRequest recorded = policy.isEmpty() ? request : request.withAttributes(attributes);
        if (verdict.grant() != null && verdict.decision() == Decision.ALLOW) {
            grants.use(verdict.grant().terms().id());
        }
        String grantId = verdict.decision() == Decision.ALLOW ? verdict.grant().terms().id().toString() : null;
        Outcome outcome = verdict.decision() == Decision.ALLOW ? Outcome.PENDING : Outcome.DENIED;
        EvidenceEntry entry = evidence.record(submission(recorded, verdict.decision(), outcome, verdict.reason(),
                grantId, now));
        DecisionRecord record = new DecisionRecord(entry.id(), request.agentId(), request.principalId(),
                request.action(), request.target(), verdict.decision(), verdict.code(),
                grantId == null ? null : UUID.fromString(grantId), entry.recordedAt(), null, null);
        decisions.insert(record);
        return new DecisionResult(record, verdict.reason(), entry, approval == null ? null : approval.id());
    }

    private static String who(Approval approval) {
        return CedarPolicies.OPERATOR.equals(approval.method()) ? CedarPolicies.OPERATOR : CedarPolicies.PRINCIPAL;
    }

    private static Verdict approved(DecisionRequest request, ApprovalService.Gate gate, Verdict granted) {
        Approval a = gate.approval();
        return switch (gate.answer()) {
            case APPROVED -> new Verdict(ReasonCode.ALLOWED_BY_APPROVAL, "Approval " + a.id() + " by the "
                    + who(a) + " allows " + describe(request) + " once under grant "
                    + granted.grant().terms().id() + ".", granted.grant());
            case PENDING -> new Verdict(ReasonCode.APPROVAL_PENDING, "Policy " + String.join(", ", a.policies())
                    + " needs the " + (a.approver().equals(CedarPolicies.ANY) ? "principal or the operator"
                    : a.approver()) + " to approve " + describe(request) + "; approval " + a.id()
                    + " waits until " + Timestamps.format(a.expiresAt()) + ".", null);
            case DENIED -> new Verdict(ReasonCode.APPROVAL_DENIED, "Approval " + a.id() + " for "
                    + describe(request) + " was denied by the " + who(a) + ".", null);
            case EXPIRED -> new Verdict(ReasonCode.APPROVAL_EXPIRED, "Approval " + a.id() + " for "
                    + describe(request) + " expired.", null);
        };
    }

    @Transactional
    public DecisionResult reportOutcome(UUID decisionId, String callerAgentId, OutcomeReport report) {
        return reportOutcome(decisionId, callerAgentId, report, false);
    }

    @Transactional
    public DecisionResult reportUndelivered(UUID decisionId, String callerAgentId, OutcomeReport report) {
        if (report.outcome() != Outcome.FAILED) {
            throw new IllegalArgumentException("An undelivered action can only fail");
        }
        return reportOutcome(decisionId, callerAgentId, report, true);
    }

    private DecisionResult reportOutcome(UUID decisionId, String callerAgentId, OutcomeReport report,
                                         boolean returnUse) {
        DecisionRecord decision = decisions.lock(decisionId)
                .filter(d -> callerAgentId == null || d.agentId().equals(callerAgentId))
                .orElseThrow(() -> LedgerException.notFound("Decision " + decisionId));
        if (report.outcome() != Outcome.SUCCEEDED && report.outcome() != Outcome.FAILED) {
            throw LedgerException.invalid("The outcome has invalid fields.",
                    Map.of("outcome", "must be SUCCEEDED or FAILED"));
        }
        if (decision.decision() == Decision.DENY) {
            throw LedgerException.conflict("DECISION_DENIED", "Decision " + decisionId + " was denied.");
        }
        if (decision.outcome() != null) {
            throw LedgerException.conflict("OUTCOME_RECORDED",
                    "The outcome of decision " + decisionId + " is already recorded.");
        }
        EvidenceEntry decided = evidence.get(decisionId);
        Map<String, String> attributes = new TreeMap<>(report.attributes() == null ? Map.of() : report.attributes());
        attributes.put(DECISION_ATTRIBUTE, decisionId.toString());
        if (returnUse && decision.grantId() != null) {
            grants.lock(decision.grantId());
            grants.release(decision.grantId());
            attributes.put(USE_RETURNED_ATTRIBUTE, "true");
        }
        EvidenceEntry entry = evidence.record(new EvidenceSubmission(clock.instant(), decision.agentId(),
                decision.principalId(), decision.action(), decision.target(), Decision.ALLOW, report.reason(),
                decided.delegationId(), decided.inputHash(), report.outputHash(), report.outcome(),
                decided.correlationId(), attributes, decided.model()));
        decisions.recordOutcome(decisionId, report.outcome(), entry.id());
        return new DecisionResult(get(decisionId, callerAgentId), report.reason(), entry);
    }

    @Transactional(readOnly = true)
    public DecisionRecord get(UUID decisionId, String callerAgentId) {
        return decisions.find(decisionId)
                .filter(d -> callerAgentId == null || d.agentId().equals(callerAgentId))
                .orElseThrow(() -> LedgerException.notFound("Decision " + decisionId));
    }

    private Verdict evaluate(DecisionRequest request, Instant now) {
        Optional<Agent> agent = agents.find(request.agentId());
        if (agent.isEmpty() || !agent.get().active()) {
            return new Verdict(ReasonCode.AGENT_NOT_ACTIVE, "Agent " + request.agentId() + " is not active.", null);
        }
        List<Grant> candidates = grants.lockActive(request.agentId(), request.principalId()).stream()
                .filter(g -> g.status(now) != GrantStatus.EXPIRED)
                .toList();
        List<Grant> covering = candidates.stream()
                .filter(g -> g.terms().covers(request.action(), request.target()))
                .toList();
        Optional<Grant> usable = covering.stream().filter(g -> g.status(now) == GrantStatus.ACTIVE).findFirst();
        if (usable.isPresent()) {
            return new Verdict(ReasonCode.ALLOWED_BY_GRANT, "Grant " + usable.get().terms().id() + " from "
                    + request.principalId() + " covers " + describe(request) + ".", usable.get());
        }
        Optional<Grant> exhausted = covering.stream()
                .filter(g -> g.status(now) == GrantStatus.EXHAUSTED).findFirst();
        if (exhausted.isPresent()) {
            return new Verdict(ReasonCode.USES_EXHAUSTED, "Grant " + exhausted.get().terms().id()
                    + " has used all " + exhausted.get().terms().maxUses() + " uses.", exhausted.get());
        }
        Optional<Grant> early = covering.stream()
                .filter(g -> g.status(now) == GrantStatus.NOT_YET_VALID).findFirst();
        if (early.isPresent()) {
            return new Verdict(ReasonCode.NOT_YET_VALID, "Grant " + early.get().terms().id() + " is not valid before "
                    + early.get().terms().notBefore() + ".", early.get());
        }
        if (!candidates.isEmpty()) {
            return new Verdict(ReasonCode.NOT_COVERED, "No active grant from " + request.principalId() + " covers "
                    + describe(request) + ".", null);
        }
        return new Verdict(ReasonCode.NO_ACTIVE_GRANT, "No active grant from " + request.principalId() + " to "
                + request.agentId() + ".", null);
    }

    private static String denial(DecisionRequest request, CedarPolicies.Verdict verdict) {
        if (!verdict.errors().isEmpty()) {
            return "The policies could not decide " + describe(request) + ": " + String.join("; ", verdict.errors());
        }
        if (verdict.policies().isEmpty()) {
            return "No policy permits " + describe(request) + ".";
        }
        return "Policy " + String.join(", ", verdict.policies()) + " forbids " + describe(request) + ".";
    }

    private static String describe(DecisionRequest request) {
        return request.target() == null ? request.action() : request.action() + " on " + request.target();
    }

    private static EvidenceSubmission submission(DecisionRequest r, Decision decision, Outcome outcome,
                                                 String reason, String grantId, Instant now) {
        return new EvidenceSubmission(now, r.agentId(), r.principalId(), r.action(), r.target(), decision,
                reason == null ? null : truncate(reason), grantId, r.inputHash(), null, outcome, r.correlationId(),
                r.attributes(), r.model());
    }

    private static String truncate(String reason) {
        return reason.length() <= 500 ? reason : reason.substring(0, 497) + "...";
    }
}
