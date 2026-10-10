package com.nexusphere.ledger.compliance.application;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
class ComplianceRecorder implements ApplicationRunner {

    static final String ACTION = "compliance/activate";

    private final Compliance compliance;
    private final EvidenceService evidence;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    ComplianceRecorder(Compliance compliance, EvidenceService evidence, NamedParameterJdbcTemplate jdbc,
                       TransactionTemplate transactions) {
        this.compliance = compliance;
        this.evidence = evidence;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        String profiles = compliance.references().stream().map(p -> p.id() + ":" + p.digest())
                .collect(Collectors.joining(","));
        String region = compliance.region() == null ? "" : compliance.region();
        transactions.executeWithoutResult(status -> {
            jdbc.queryForList("select sequence from ledger.ledger_head where id = 1 for update", Map.of());
            List<Map<String, Object>> last = jdbc.queryForList("""
                    select (select value from ledger.evidence_attribute a
                            where a.evidence_id = r.id and a.name = 'profiles') as profiles,
                           (select value from ledger.evidence_attribute a
                            where a.evidence_id = r.id and a.name = 'region') as region
                    from ledger.evidence_record r where r.action = :action
                    order by r.sequence desc limit 1
                    """, Map.of("action", ACTION));
            if (!last.isEmpty() && profiles.equals(last.getFirst().get("profiles"))
                    && region.equals(last.getFirst().get("region") == null ? "" : last.getFirst().get("region"))) {
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
