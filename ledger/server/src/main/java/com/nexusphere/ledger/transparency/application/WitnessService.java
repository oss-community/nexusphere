package com.nexusphere.ledger.transparency.application;

import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.transparency.domain.repository.WitnessedLogRepository;
import com.nexusphere.ledger.transparency.domain.repository.WitnessedLogRepository.WitnessedLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class WitnessService {

    public sealed interface Result {
    }

    public record Cosigned(String line) implements Result {
    }

    public record Conflict(long size) implements Result {
    }

    public record Rejected(int status, String message) implements Result {
    }

    private final Map<String, NoteKey> watched;
    private final WitnessedLogRepository logs;
    private final LedgerSigner signer;
    private final TransparencyLog transparencyLog;
    private final Clock clock;

    WitnessService(LedgerProperties properties, WitnessedLogRepository logs, LedgerSigner signer,
                   TransparencyLog transparencyLog, Clock clock) {
        this.logs = logs;
        this.signer = signer;
        this.transparencyLog = transparencyLog;
        this.clock = clock;
        this.watched = new HashMap<>();
        LedgerProperties.Log settings = properties.log();
        if (settings != null && settings.watched() != null) {
            settings.watched().forEach((name, log) -> {
                if (log == null || log.key() == null || log.key().isBlank()) {
                    throw new IllegalStateException("ledger.log.watched." + name + ".key must be set");
                }
                NoteKey key = NoteKey.parse(log.key());
                if (key.type() != NoteKey.ED25519) {
                    throw new IllegalStateException("ledger.log.watched." + name + ".key is not a log key");
                }
                watched.put(key.name(), key);
            });
        }
    }

    public boolean enabled() {
        return !watched.isEmpty();
    }

    @Transactional
    public Result addCheckpoint(String request) {
        int blank = request.indexOf("\n\n");
        if (blank < 0 || !request.startsWith("old ")) {
            return new Rejected(400, "the request has an old size, a proof, a blank line and a checkpoint");
        }
        String[] head = request.substring(0, blank).split("\n");
        long old;
        List<byte[]> proof = new ArrayList<>();
        LogCheckpoint.Note note;
        try {
            old = Long.parseLong(head[0].substring(4));
            for (int i = 1; i < head.length; i++) {
                proof.add(Base64.getDecoder().decode(head[i]));
            }
            note = LogCheckpoint.parse(request.substring(blank + 2));
        } catch (IllegalArgumentException e) {
            return new Rejected(400, e.getMessage());
        }
        NoteKey key = watched.get(note.checkpoint().origin());
        if (key == null) {
            return new Rejected(404, "this witness does not watch " + note.checkpoint().origin());
        }
        if (!note.signedBy(key)) {
            return new Rejected(403, "the checkpoint is not signed by the key of " + key.name());
        }
        Instant now = clock.instant();
        WitnessedLog state = logs.lock(key.name(), MerkleTree.emptyRoot(), now);
        if (old != state.size()) {
            return new Conflict(state.size());
        }
        long size = note.checkpoint().size();
        if (size < old) {
            return new Rejected(400, "the checkpoint is smaller than the old size");
        }
        if (!MerkleTree.verifyConsistency(old, size, proof, state.root(), note.checkpoint().root())) {
            return new Rejected(422, "the consistency proof does not hold");
        }
        logs.update(new WitnessedLog(key.name(), size, note.checkpoint().root(), now));
        return new Cosigned(signer.cosign(transparencyLog.origin(), note.body(), now.getEpochSecond()).line());
    }
}
