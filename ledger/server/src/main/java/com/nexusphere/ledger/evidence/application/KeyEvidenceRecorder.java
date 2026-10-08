package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.signing.SigningKey;
import com.nexusphere.ledger.server.signing.SigningKeyStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
class KeyEvidenceRecorder implements ApplicationRunner {

    static final String AGENT_ID = "ledger";
    static final String PRINCIPAL_ID = "operator";

    private final SigningKeyStore store;
    private final EvidenceService evidence;
    private final TransactionTemplate transactions;

    KeyEvidenceRecorder(SigningKeyStore store, EvidenceService evidence, TransactionTemplate transactions) {
        this.store = store;
        this.evidence = evidence;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(status -> {
            store.lock();
            store.all().stream().filter(key -> key.evidenceSequence() == null).forEach(this::record);
        });
    }

    private void record(SigningKey key) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("algorithm", SigningKeys.ALGORITHM);
        attributes.put("publicKey", key.publicKey().encoded());
        if (key.rotation() != null) {
            attributes.put("previousKeyId", key.previousKeyId());
            attributes.put("endorsed", Boolean.toString(key.rotation().endorsed()));
        }
        EvidenceEntry entry = evidence.record(new EvidenceSubmission(key.activatedAt(), AGENT_ID, PRINCIPAL_ID,
                key.rotation() == null ? "key/activate" : "key/rotate", key.keyId(), null, null, null, null, null,
                Outcome.SUCCEEDED, null, attributes));
        store.markRecorded(key.keyId(), entry.sequence());
    }
}
