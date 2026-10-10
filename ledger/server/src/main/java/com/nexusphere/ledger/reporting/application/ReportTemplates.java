package com.nexusphere.ledger.reporting.application;

import com.nexusphere.ledger.reporting.application.ReportSection.Status;
import com.nexusphere.ledger.verifier.PackageReport;
import tools.jackson.databind.JsonNode;

import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.nexusphere.ledger.reporting.application.ReportFacts.erased;
import static com.nexusphere.ledger.reporting.application.ReportFacts.has;
import static com.nexusphere.ledger.reporting.application.ReportFacts.is;
import static com.nexusphere.ledger.reporting.application.ReportFacts.model;

public final class ReportTemplates {

    public static final String DISPUTE = "dispute";

    public record Template(String id, String title, String description,
                          Function<ReportFacts, List<ReportSection>> sections) {
    }

    private static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();

    static {
        add(new Template(DISPUTE, "Dispute package",
                "Everything the evidence shows about one incident, in order, for an insurer, arbitrator or court",
                ReportTemplates::dispute));
        add(new Template("dora-ict-records", "DORA ICT records",
                "Maps the evidence to the record keeping of the EU Digital Operational Resilience Act",
                ReportTemplates::dora));
        add(new Template("eu-ai-act-logging", "EU AI Act record keeping",
                "Maps the evidence to the logging duties of Articles 12, 19 and 26(6) of the EU AI Act",
                ReportTemplates::aiAct));
        add(new Template("finra-supervision", "FINRA supervision",
                "Maps the evidence to the supervision of FINRA Rule 3110", ReportTemplates::finra));
        add(new Template("sec-17a-4-records", "SEC Rule 17a-4 records",
                "Maps the evidence to the electronic record keeping of SEC Rule 17a-4", ReportTemplates::sec));
    }

    private ReportTemplates() {
    }

    public static Set<String> ids() {
        return TEMPLATES.keySet();
    }

    public static Optional<Template> find(String id) {
        return Optional.ofNullable(TEMPLATES.get(id));
    }

    private static void add(Template template) {
        TEMPLATES.put(template.id(), template);
    }

    private static List<ReportSection> dispute(ReportFacts facts) {
        List<String> timeline = new ArrayList<>();
        for (JsonNode entry : facts.entries()) {
            timeline.add("#" + entry.path("sequence").asLong() + " " + entry.path("occurredAt").asString() + ": "
                    + entry.path("agentId").asString() + " " + entry.path("action").asString() + " on "
                    + (entry.path("erased").asBoolean(false) ? "an erased target"
                    : entry.path("target").asString("no target")) + ", decision "
                    + entry.path("decision").asString("none") + ", outcome " + entry.path("outcome").asString("none")
                    + (entry.hasNonNull("reason") ? " (" + entry.path("reason").asString() + ")" : ""));
        }
        List<ReportSection> sections = new ArrayList<>();
        sections.add(new ReportSection("timeline", "Incident timeline", "What the agents did, in order",
                Status.share(facts.total(), facts.total()), limit(timeline), facts.sequences(entry -> true)));
        long delegated = facts.count(has("delegationId"));
        List<String> authority = new ArrayList<>();
        authority.add(delegated + " of " + facts.total() + " entries name the grant they acted under"
                + (facts.values("delegationId").isEmpty() ? "" : ": " + String.join(", ",
                facts.values("delegationId"))));
        List<Long> denied = facts.sequences(is("outcome", "DENIED"));
        authority.add(denied.isEmpty() ? "No action was denied" : "Denied: entries " + join(denied));
        sections.add(new ReportSection("authority", "Authority", "Under which grant and decision each action ran",
                Status.share(facts.count(has("delegationId").or(is("outcome", "DENIED"))), facts.total()), authority,
                facts.sequences(has("delegationId"))));
        sections.add(outcomes(facts, "outcomes", "Outcomes", "What happened after each action that was not denied"));
        long named = facts.count(model("name"));
        List<String> model = new ArrayList<>();
        model.add(named + " of " + facts.total() + " entries name the model the agent ran"
                + (facts.models().isEmpty() ? "" : ": " + String.join(", ", facts.models())));
        model.add(facts.count(model("promptHash")) + " entries name the hash of their prompt"
                + (facts.modelValues("promptHash").isEmpty() ? ""
                : ", " + facts.modelValues("promptHash").size() + " distinct"));
        model.add(facts.count(model("configHash")) + " entries name the hash of their configuration");
        sections.add(new ReportSection("model", "Model", "Which model, version and prompt each agent ran",
                Status.share(named, facts.total()), model, facts.sequences(model("name"))));
        sections.add(integrity(facts, "integrity", "Integrity",
                "The evidence has not been changed since it was recorded"));
        List<String> disclosure = List.of(
                facts.links() + " links of the hash chain are in the package and " + facts.total()
                        + " entries are disclosed; the others show only their hashes",
                facts.count(erased()) + " disclosed entries had their personal fields erased and are proven by"
                        + " their commitments");
        sections.add(new ReportSection("disclosure", "Disclosure", "What the package shows and what it hides",
                Status.SHOWN, disclosure, facts.sequences(erased())));
        return sections;
    }

    private static List<ReportSection> aiAct(ReportFacts facts) {
        List<ReportSection> sections = new ArrayList<>();
        sections.add(new ReportSection("art-12-1", "EU AI Act Article 12(1)",
                "The system records events automatically over its lifetime",
                facts.verification().valid() ? Status.share(facts.total(), facts.total()) : Status.NOT_SHOWN,
                List.of(facts.total() + " events recorded from " + facts.first() + " to " + facts.last()
                        + ", each linked to the one before in a hash chain")
                , List.of()));
        List<Long> denied = facts.sequences(is("outcome", "DENIED"));
        List<Long> failed = facts.sequences(is("outcome", "FAILED"));
        sections.add(new ReportSection("art-12-2", "EU AI Act Article 12(2)",
                "The records let situations that may present a risk be identified",
                Status.share(facts.count(has("decision")), facts.total()),
                List.of(facts.count(has("decision")) + " of " + facts.total() + " events record the decision taken",
                        denied.isEmpty() ? "No action was denied" : "Denied: entries " + join(denied),
                        failed.isEmpty() ? "No action failed" : "Failed: entries " + join(failed)),
                concat(denied, failed)));
        long inputs = facts.count(has("inputHash"));
        sections.add(new ReportSection("art-12-3", "EU AI Act Article 12(3)",
                "The input of each use can be traced", Status.share(inputs, facts.total()),
                List.of(inputs + " of " + facts.total() + " events record the hash of their input"),
                facts.sequences(has("inputHash").negate())));
        Predicate<JsonNode> traced = model("name").and(model("version")).and(model("promptHash"));
        long models = facts.count(traced);
        sections.add(new ReportSection("art-12-2-a", "EU AI Act Article 12(2)(a)",
                "The records show which model, version and prompt produced each event, so a substantial"
                        + " modification can be identified",
                Status.share(models, facts.total()),
                List.of(models + " of " + facts.total() + " events name the model, its version and the hash of"
                                + " the prompt",
                        facts.models().isEmpty() ? "No event names a model"
                                : "Models: " + String.join(", ", facts.models())),
                facts.sequences(traced.negate())));
        sections.add(retention(facts, "art-19-1", "EU AI Act Articles 19(1) and 26(6)",
                "Logs are kept for at least six months", Period.ofMonths(6)));
        sections.add(integrity(facts, "integrity", "EU AI Act Article 12",
                "The records cannot be changed unnoticed"));
        return sections;
    }

    private static List<ReportSection> dora(ReportFacts facts) {
        List<ReportSection> sections = new ArrayList<>();
        sections.add(integrity(facts, "art-9", "DORA Article 9(3)",
                "The integrity and authenticity of the data are protected"));
        sections.add(outcomes(facts, "art-17", "DORA Article 17",
                "ICT-related incidents are detected and recorded"));
        Set<String> services = facts.services();
        long erased = facts.count(erased());
        sections.add(new ReportSection("art-28", "DORA Article 28(3)",
                "The ICT services of third parties in use are known",
                services.isEmpty() ? Status.NOT_SHOWN : erased > 0 ? Status.PARTLY_SHOWN : Status.SHOWN,
                List.of(services.isEmpty() ? "No targets are recorded" : "Services called: " + String.join(", ",
                        services), erased + " entries had their targets erased"), List.of()));
        long delegated = facts.count(has("delegationId"));
        sections.add(new ReportSection("art-9-4", "DORA Article 9(4)(c)",
                "Access is limited to what each agent needs", Status.share(delegated, facts.total()),
                List.of(delegated + " of " + facts.total() + " actions ran under a grant with its own limits"),
                facts.sequences(has("delegationId").negate())));
        return sections;
    }

    private static List<ReportSection> finra(ReportFacts facts) {
        List<ReportSection> sections = new ArrayList<>();
        long decided = facts.count(has("decision"));
        sections.add(new ReportSection("rule-3110-a", "FINRA Rule 3110(a)",
                "Each agent's activity is supervised", Status.share(decided, facts.total()),
                List.of(decided + " of " + facts.total() + " actions were decided before they ran, for agents "
                        + String.join(", ", facts.values("agentId"))), List.of()));
        long delegated = facts.count(has("delegationId"));
        sections.add(new ReportSection("rule-3110-b", "FINRA Rule 3110(b)",
                "Actions follow written authorizations", Status.share(delegated, facts.total()),
                List.of(delegated + " of " + facts.total() + " actions ran under a grant"),
                facts.sequences(has("delegationId").negate())));
        List<Long> exceptions = concat(facts.sequences(is("outcome", "DENIED")), facts.sequences(is("outcome",
                "FAILED")));
        sections.add(new ReportSection("review", "FINRA Rule 3110(b)(2)",
                "Exceptions are available for review", Status.SHOWN,
                List.of(exceptions.isEmpty() ? "No denied or failed actions to review"
                        : exceptions.size() + " denied or failed actions to review: entries " + join(exceptions)),
                exceptions));
        return sections;
    }

    private static List<ReportSection> sec(ReportFacts facts) {
        List<ReportSection> sections = new ArrayList<>();
        PackageReport verification = facts.verification();
        sections.add(new ReportSection("rule-17a-4-f", "SEC Rule 17a-4(f)(2)",
                "Records are kept so they cannot be rewritten or erased unnoticed",
                verification.valid() ? Status.SHOWN : Status.NOT_SHOWN,
                List.of("Evidence is append-only in the database and each entry is linked to the one before",
                        verification.valid() ? "The chain of " + verification.checkedLinks()
                                + " links verifies up to checkpoint " + verification.checkpointSequence()
                                : "The package does not verify: " + String.join("; ", verification.problems())),
                List.of()));
        sections.add(retention(facts, "rule-17a-4-b", "SEC Rule 17a-4(b)",
                "Records are kept for at least six years", Period.ofYears(6)));
        sections.add(new ReportSection("time", "SEC Rule 17a-4(f)(2)", "Each record has a reliable date and time",
                verification.timestampedAt() != null ? Status.SHOWN : Status.PARTLY_SHOWN,
                List.of(verification.timestampedAt() != null ? "The checkpoint was timestamped at "
                        + verification.timestampedAt() + " by " + verification.timestampAuthority()
                        : "Times come from the ledger's clock only; no timestamp authority vouches for them"),
                List.of()));
        boolean copy = verification.rekorLogIndex() != null || !verification.witnesses().isEmpty();
        sections.add(new ReportSection("copy", "SEC Rule 17a-4(f)(3)",
                "A copy of the record is held apart from the original", copy ? Status.SHOWN : Status.NOT_SHOWN,
                List.of(copy ? anchors(verification) : "No copy outside the ledger is proven"), List.of()));
        return sections;
    }

    private static ReportSection outcomes(ReportFacts facts, String id, String reference, String requirement) {
        long ran = facts.count(is("outcome", "DENIED").negate());
        long settled = facts.count(is("outcome", "SUCCEEDED").or(is("outcome", "FAILED")));
        List<Long> failed = facts.sequences(is("outcome", "FAILED"));
        return new ReportSection(id, reference, requirement, Status.share(settled, ran),
                List.of(settled + " of " + ran + " actions that were not denied report a final outcome",
                        facts.count(is("outcome", "SUCCEEDED")) + " succeeded, " + failed.size() + " failed"
                                + (failed.isEmpty() ? "" : " (entries " + join(failed) + ")") + " and "
                                + facts.count(is("outcome", "PENDING")) + " are still pending"),
                failed);
    }

    private static ReportSection retention(ReportFacts facts, String id, String reference, String requirement,
                                           Period required) {
        List<String> findings = new ArrayList<>();
        findings.add("Entries stay in the hash chain for good; only personal fields can be erased");
        Status status = Status.SHOWN;
        for (String action : facts.values("action")) {
            Optional<Period> minimum = facts.minimum(action);
            Optional<Period> maximum = facts.maximum(action);
            findings.add(action + ": personal fields kept at least " + minimum.map(Period::toString)
                    .orElse("until erased on request") + " and at most " + maximum.map(Period::toString)
                    .orElse("without limit"));
            if (maximum.isPresent() && !ReportFacts.atLeast(maximum.get(), required)) {
                status = Status.NOT_SHOWN;
            } else if ((minimum.isEmpty() || !ReportFacts.atLeast(minimum.get(), required))
                    && status == Status.SHOWN) {
                status = Status.PARTLY_SHOWN;
            }
        }
        return new ReportSection(id, reference, requirement, status, findings, List.of());
    }

    private static ReportSection integrity(ReportFacts facts, String id, String reference, String requirement) {
        PackageReport verification = facts.verification();
        List<String> findings = new ArrayList<>();
        if (verification.valid()) {
            findings.add("The hash chain of " + verification.checkedLinks() + " links verifies up to checkpoint "
                    + verification.checkpointSequence() + ", signed by key " + verification.keyId());
        } else {
            findings.add("The package does not verify: " + String.join("; ", verification.problems()));
        }
        findings.add(verification.timestampedAt() == null ? "Not timestamped by a timestamp authority"
                : "Timestamped at " + verification.timestampedAt() + " by " + verification.timestampAuthority());
        findings.add(anchors(verification));
        Status status = !verification.valid() ? Status.NOT_SHOWN
                : facts.anchored() ? Status.SHOWN : Status.PARTLY_SHOWN;
        return new ReportSection(id, reference, requirement, status, findings, List.of());
    }

    private static String anchors(PackageReport verification) {
        String rekor = verification.rekorLogIndex() == null ? "Not anchored in a Rekor log"
                : "Anchored as entry " + verification.rekorLogIndex() + " in the Rekor log "
                + verification.rekorOrigin();
        String witnesses = verification.witnesses().isEmpty() ? "no witness cosigned the log"
                : "cosigned by " + String.join(", ", verification.witnesses());
        return rekor + "; " + witnesses;
    }

    private static List<String> limit(List<String> findings) {
        if (findings.size() <= ReportSection.MAX_FINDINGS) {
            return findings;
        }
        List<String> limited = new ArrayList<>(findings.subList(0, ReportSection.MAX_FINDINGS));
        limited.add("and " + (findings.size() - ReportSection.MAX_FINDINGS) + " more in the package");
        return limited;
    }

    private static List<Long> concat(List<Long> first, List<Long> second) {
        TreeSet<Long> all = new TreeSet<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private static String join(List<Long> sequences) {
        List<String> shown = sequences.stream().limit(50).map(String::valueOf).toList();
        return String.join(", ", shown) + (sequences.size() > 50 ? " and " + (sequences.size() - 50) + " more" : "");
    }
}
