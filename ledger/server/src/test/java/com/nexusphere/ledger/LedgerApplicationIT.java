package com.nexusphere.ledger;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.evidence.application.CheckpointService;
import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.evidence.application.VerificationReport;
import com.nexusphere.ledger.evidence.application.VerificationService;
import com.nexusphere.ledger.evidence.domain.model.Decision;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles({"postgresql", "dev"})
@Import(LedgerContainers.class)
class LedgerApplicationIT {

    @Autowired
    EvidenceService evidence;

    @Autowired
    CheckpointService checkpoints;

    @Autowired
    VerificationService verification;

    @Autowired
    LedgerSigner signer;

    @Autowired
    JdbcTemplate jdbc;

    private EvidenceEntry record(String agentId) {
        return evidence.record(new EvidenceSubmission(null, agentId, "user-1", "tools/call", "search",
                Decision.ALLOW, null, null, null, null, Outcome.SUCCEEDED, null, Map.of("tool", "search")));
    }

    @Test
    void migrationsCreateTheLedgerSchema() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'ledger'", String.class);

        assertThat(tables).contains("ledger_head", "evidence_record", "evidence_attribute", "checkpoint",
                "flyway_schema_history");
    }

    @Test
    void evidenceCannotBeChangedOrDeleted() {
        EvidenceEntry entry = record("agent-immutable");

        assertThatThrownBy(() -> jdbc.update("update ledger.evidence_record set agent_id = 'x' where id = ?",
                entry.id())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger.evidence_attribute where evidence_id = ?",
                entry.id())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("truncate ledger.evidence_record cascade"))
                .hasMessageContaining("append-only");
    }

    @Test
    void concurrentWritersProduceOneUnbrokenChain() throws Exception {
        long before = evidence.head().sequence();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<EvidenceEntry>> tasks = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            String agent = "agent-" + (i % 8);
            tasks.add(() -> record(agent));
        }
        List<Long> sequences = new ArrayList<>();
        for (Future<EvidenceEntry> future : pool.invokeAll(tasks)) {
            sequences.add(future.get().sequence());
        }
        pool.shutdown();

        assertThat(sequences).doesNotHaveDuplicates().hasSize(80);
        assertThat(evidence.head().sequence()).isEqualTo(before + 80);
        assertThat(verification.verify().valid()).isTrue();
    }

    @Test
    void verificationFindsEvidenceChangedBehindTheLedgersBack() {
        EvidenceEntry entry = record("agent-tamper");
        record("agent-after-tamper");

        bypassTriggers(() -> jdbc.update("update ledger.evidence_record set principal_id = 'someone-else' where id = ?",
                entry.id()));
        VerificationReport tampered = verification.verify();
        bypassTriggers(() -> jdbc.update("update ledger.evidence_record set principal_id = ? where id = ?",
                entry.principalId(), entry.id()));

        assertThat(tampered.valid()).isFalse();
        assertThat(tampered.failedSequence()).isEqualTo(entry.sequence());
        assertThat(tampered.failure()).contains("hash");
        assertThat(verification.verify().valid()).isTrue();
    }

    @Test
    void verificationRejectsAForgedCheckpoint() {
        EvidenceEntry entry = record("agent-forged-checkpoint");
        SignedCheckpoint genuine = checkpoints.create();
        assertThat(genuine.checkpoint().sequence()).isEqualTo(entry.sequence());

        bypassTriggers(() -> jdbc.update("update ledger.checkpoint set signature = ? where sequence = ?",
                "AAAA" + genuine.signature().substring(4), entry.sequence()));
        VerificationReport forged = verification.verify();
        bypassTriggers(() -> jdbc.update("update ledger.checkpoint set signature = ? where sequence = ?",
                genuine.signature(), entry.sequence()));

        assertThat(forged.valid()).isFalse();
        assertThat(forged.failedSequence()).isEqualTo(entry.sequence());
        assertThat(forged.failure()).contains("signature");
        assertThat(verification.verify().valid()).isTrue();
    }

    @Test
    void checkpointsAreSignedWithTheLedgerKeyAndAreIdempotent() {
        record("agent-checkpoint");

        SignedCheckpoint first = checkpoints.create();
        SignedCheckpoint second = checkpoints.create();

        assertThat(second).isEqualTo(first);
        assertThat(first.verify(signer.publicKey())).isTrue();
        assertThat(first.checkpoint().headHash()).isEqualTo(evidence.head().hash());
    }

    private void bypassTriggers(Runnable change) {
        jdbc.execute("alter table ledger.evidence_record disable trigger evidence_record_append_only");
        jdbc.execute("alter table ledger.checkpoint disable trigger checkpoint_append_only");
        try {
            change.run();
        } finally {
            jdbc.execute("alter table ledger.evidence_record enable trigger evidence_record_append_only");
            jdbc.execute("alter table ledger.checkpoint enable trigger checkpoint_append_only");
        }
    }
}
