package com.nexusphere.ledger.compliance.application;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
class ComplianceRecorder implements ApplicationRunner {

    static final String ACTION = "compliance/activate";

    private final Compliance compliance;
    private final EvidenceService evidence;
    private final EvidenceRepository repository;
    private final TransactionTemplate transactions;

    ComplianceRecorder(Compliance compliance, EvidenceService evidence, EvidenceRepository repository,
                       TransactionTemplate transactions) {
        this.compliance = compliance;
        this.evidence = evidence;
        this.repository = repository;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        String profiles = compliance.references().stream().map(p -> p.id() + ":" + p.digest())
                .collect(Collectors.joining(","));
        String region = compliance.region() == null ? "" : compliance.region();
        transactions.executeWithoutResult(status -> {
            repository.lockHead();
            Optional<EvidenceEntry> last = repository.latestByAction(ACTION);
            if (last.isPresent() && profiles.equals(last.get().attributes().get("profiles"))
                    && region.equals(last.get().attributes().getOrDefault("region", ""))) {
                return;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("profiles", profiles);
            if (!region.isEmpty()) {
                attributes.put("region", region);
            }
            evidence.record(new EvidenceSubmission(null, "ledger", "operator", ACTION,
                    compliance.references().stream().map(Checkpoint.Profile::id).collect(Collectors.joining(",")),
                    null, null, null, null, null, Outcome.SUCCEEDED, null, attributes));
        });
    }
}
