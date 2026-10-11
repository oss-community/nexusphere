package com.nexusphere.ledger.ask.application;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSearch;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.web.LedgerException;
import com.nexusphere.ledger.transparency.application.TransparencyLog;
import com.nexusphere.ledger.transparency.domain.repository.LogCheckpointRepository.StoredCheckpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AskService {

    public record Checks(boolean content, boolean link, boolean inclusion) {

        public boolean passed() {
            return content && link && inclusion;
        }
    }

    public record Checked(EvidenceEntry entry, Checks checks) {
    }

    public record Result(String question, String answer, List<Citations.Sentence> sentences, int droppedSentences,
                         List<Checked> citations, List<Long> unverified, QueryPlan plan, String model,
                         long considered, Long checkpointSize, long recordedAs) {
    }

    static final String ACTION = "ask/question";
    static final int MAX_QUESTION = 500;
    static final String UNTIED = "The model's answer could not be tied to the evidence, so it is not shown.";

    private final EvidenceService evidence;
    private final TransparencyLog log;
    private final TransactionTemplate transactions;
    private final LanguageModel model;
    private final int maxEvidence;
    private final Clock clock;

    @Autowired
    AskService(EvidenceService evidence, TransparencyLog log, TransactionTemplate transactions,
               LedgerProperties properties, Clock clock) {
        this(evidence, log, transactions, model(properties.ask()), properties.ask() == null ? 50
                : Math.max(1, properties.ask().maxEvidence()), clock);
    }

    AskService(EvidenceService evidence, TransparencyLog log, TransactionTemplate transactions, LanguageModel model,
               int maxEvidence, Clock clock) {
        this.evidence = evidence;
        this.log = log;
        this.transactions = transactions;
        this.model = model;
        this.maxEvidence = maxEvidence;
        this.clock = clock;
    }

    static LanguageModel model(LedgerProperties.Ask ask) {
        if (ask == null || ask.model() == null || ask.model().isBlank() || BuiltinModel.NAME.equals(ask.model())) {
            return new BuiltinModel();
        }
        if (!"openai".equals(ask.model().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("LEDGER_ASK_MODEL must be builtin or openai, not " + ask.model());
        }
        return new OpenAiCompatibleModel(ask.url(), ask.name(), ask.apiKey(), ask.timeout());
    }

    public String modelName() {
        return model.name();
    }

    public Result ask(String question, String principal) {
        String asked = question == null ? "" : question.strip();
        if (asked.isEmpty() || asked.length() > MAX_QUESTION) {
            throw LedgerException.invalid("The question has invalid fields.",
                    Map.of("question", "must have 1 to " + MAX_QUESTION + " characters"));
        }
        Instant now = clock.instant();
        PlanContext context = new PlanContext(now, evidence.agentIds(500), evidence.actions(500), principal);
        QueryPlan plan;
        try {
            plan = model.plan(asked, context).scopedTo(principal);
        } catch (ModelException e) {
            throw new LedgerException(HttpStatus.BAD_GATEWAY, "MODEL_UNAVAILABLE", e.getMessage(), Map.of());
        }
        List<EvidenceEntry> found = find(plan);
        Verified verified = transactions.execute(status -> verify(found, now));
        List<EvidenceEntry> trusted = verified.checked().stream().filter(c -> c.checks().passed())
                .map(Checked::entry).sorted(Comparator.comparingLong(EvidenceEntry::sequence)).toList();
        String text;
        try {
            text = trusted.isEmpty() ? BuiltinModel.NO_EVIDENCE : model.answer(asked, plan, trusted);
        } catch (ModelException e) {
            throw new LedgerException(HttpStatus.BAD_GATEWAY, "MODEL_UNAVAILABLE", e.getMessage(), Map.of());
        }
        Set<Long> allowed = trusted.stream().map(EvidenceEntry::sequence).collect(Collectors.toSet());
        Citations.Parsed parsed = Citations.parse(text, allowed);
        String answer;
        List<Citations.Sentence> sentences = parsed.sentences();
        if (trusted.isEmpty()) {
            answer = BuiltinModel.NO_EVIDENCE;
            sentences = List.of();
        } else if (sentences.isEmpty()) {
            answer = UNTIED;
        } else {
            answer = sentences.stream().map(Citations.Sentence::text).collect(Collectors.joining(" "));
        }
        Set<Long> cited = new LinkedHashSet<>();
        sentences.forEach(sentence -> cited.addAll(sentence.citations()));
        Map<Long, Checked> bySequence = new LinkedHashMap<>();
        verified.checked().forEach(c -> bySequence.put(c.entry().sequence(), c));
        List<Checked> citations = cited.stream().sorted().map(bySequence::get).toList();
        List<Long> unverified = verified.checked().stream().filter(c -> !c.checks().passed())
                .map(c -> c.entry().sequence()).sorted().toList();
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("ask.model", model.name());
        attributes.put("ask.citations", cited.stream().sorted().map(String::valueOf)
                .collect(Collectors.joining(",")));
        attributes.put("ask.considered", String.valueOf(found.size()));
        EvidenceEntry recorded = evidence.record(new EvidenceSubmission(now, "ledger",
                principal == null ? "operator" : principal, ACTION, null, null, null, null, sha256(asked),
                sha256(answer), Outcome.SUCCEEDED, null, attributes));
        return new Result(asked, answer, sentences, parsed.dropped(), citations, unverified, plan, model.name(),
                found.size(), verified.size(), recorded.sequence());
    }

    private List<EvidenceEntry> find(QueryPlan plan) {
        int limit = plan.target() == null ? maxEvidence : maxEvidence * 10;
        List<EvidenceEntry> entries = evidence.search(new EvidenceSearch(plan.agentId(), plan.principalId(),
                plan.action(), plan.decision(), plan.outcome(), plan.from(), plan.to(), limit));
        List<EvidenceEntry> matching = new ArrayList<>();
        String target = plan.target() == null ? null : plan.target().toLowerCase(Locale.ROOT);
        for (EvidenceEntry entry : entries) {
            if (ACTION.equals(entry.action())) {
                continue;
            }
            if (target == null || entry.target() != null && entry.target().toLowerCase(Locale.ROOT).contains(target)) {
                matching.add(entry);
            }
            if (matching.size() == maxEvidence) {
                break;
            }
        }
        return matching;
    }

    private record Verified(List<Checked> checked, Long size) {
    }

    private Verified verify(List<EvidenceEntry> entries, Instant now) {
        if (entries.isEmpty()) {
            return new Verified(List.of(), null);
        }
        long head = evidence.head().sequence();
        StoredCheckpoint checkpoint = log.checkpoint(head, now);
        boolean signed = LogCheckpoint.parse(checkpoint.note()).signedBy(log.noteKey())
                && Arrays.equals(LogCheckpoint.parse(checkpoint.note()).checkpoint().root(), checkpoint.root());
        Map<Long, List<byte[]>> proofs = log.inclusionProofs(entries.stream().map(EvidenceEntry::sequence)
                .sorted().toList(), checkpoint.size());
        List<Checked> checked = new ArrayList<>();
        for (EvidenceEntry entry : entries) {
            boolean content = entry.computeHash().equals(entry.hash());
            String previous = entry.sequence() == 1 ? Hashes.GENESIS
                    : evidence.hashes(entry.sequence() - 2, 1).stream().findFirst().orElse(null);
            boolean link = entry.previousHash().equals(previous);
            boolean inclusion = signed && MerkleTree.verifyInclusion(MerkleTree.leafHash(TransparencyLog.leaf(entry)),
                    entry.sequence() - 1, checkpoint.size(), proofs.get(entry.sequence()), checkpoint.root());
            checked.add(new Checked(entry, new Checks(content, link, inclusion)));
        }
        return new Verified(checked, checkpoint.size());
    }

    private static String sha256(String text) {
        return Hashes.sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
