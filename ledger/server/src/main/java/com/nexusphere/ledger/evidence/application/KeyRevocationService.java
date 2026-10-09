package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.chain.KeyRevocation;
import com.nexusphere.ledger.chain.Timestamps;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class KeyRevocationService {

    static final int MAX_REASON_LENGTH = 500;

    private final LedgerSigner signer;
    private final EvidenceService evidence;
    private final Clock clock;

    KeyRevocationService(LedgerSigner signer, EvidenceService evidence, Clock clock) {
        this.signer = signer;
        this.evidence = evidence;
        this.clock = clock;
    }

    @Transactional
    public KeyRevocation revoke(String keyId, Instant compromisedAt, String reason) {
        Map<String, Object> errors = new LinkedHashMap<>();
        if (compromisedAt == null) {
            errors.put("compromisedAt", "is required");
        }
        if (reason == null || reason.isBlank()) {
            errors.put("reason", "is required");
        } else if (reason.length() > MAX_REASON_LENGTH) {
            errors.put("reason", "must have at most " + MAX_REASON_LENGTH + " characters");
        }
        if (!errors.isEmpty()) {
            throw LedgerException.invalid("The revocation has invalid fields.", errors);
        }
        KeyRevocation revocation = signer.revoke(keyId, compromisedAt, reason.strip(), clock.instant());
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("compromisedAt", Timestamps.format(revocation.compromisedAt()));
        attributes.put("reason", revocation.reason());
        attributes.put("revokerKeyId", revocation.revokerKeyId());
        evidence.record(new EvidenceSubmission(revocation.revokedAt(), KeyEvidenceRecorder.AGENT_ID,
                KeyEvidenceRecorder.PRINCIPAL_ID, "key/revoke", keyId, null, null, null, null, null,
                Outcome.SUCCEEDED, null, attributes));
        return revocation;
    }
}
