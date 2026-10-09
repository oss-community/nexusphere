package com.nexusphere.ledger.transparency.application;

import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.transparency.domain.repository.LogCheckpointRepository;
import com.nexusphere.ledger.transparency.domain.repository.LogCheckpointRepository.Cosignature;
import com.nexusphere.ledger.transparency.domain.repository.LogCheckpointRepository.StoredCheckpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class WitnessCollector {

    record Witness(String name, URI url, NoteKey key) {
    }

    private static final Logger log = LoggerFactory.getLogger(WitnessCollector.class);

    private final TransparencyLog transparencyLog;
    private final LogCheckpointRepository checkpoints;
    private final List<Witness> witnesses;
    private final HttpClient http;
    private final Duration timeout;
    private final Clock clock;

    WitnessCollector(TransparencyLog transparencyLog, LogCheckpointRepository checkpoints,
                     LedgerProperties properties, Clock clock) {
        this.transparencyLog = transparencyLog;
        this.checkpoints = checkpoints;
        this.clock = clock;
        LedgerProperties.Log settings = properties.log();
        this.timeout = settings == null || settings.witnessTimeout() == null
                ? Duration.ofSeconds(10) : settings.witnessTimeout();
        this.witnesses = witnesses(settings == null ? null : settings.witnesses());
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    private static List<Witness> witnesses(Map<String, LedgerProperties.Log.Witness> configured) {
        List<Witness> witnesses = new ArrayList<>();
        if (configured == null) {
            return witnesses;
        }
        configured.forEach((name, witness) -> {
            if (witness == null || witness.url() == null || witness.key() == null || witness.key().isBlank()) {
                throw new IllegalStateException("ledger.log.witnesses." + name + " needs a url and a key");
            }
            NoteKey key = NoteKey.parse(witness.key());
            if (key.type() != NoteKey.COSIGNATURE) {
                throw new IllegalStateException("ledger.log.witnesses." + name + ".key is not a cosignature key");
            }
            String url = witness.url().toString().replaceAll("/+$", "");
            witnesses.add(new Witness(key.name(), URI.create(url + "/add-checkpoint"), key));
        });
        return List.copyOf(witnesses);
    }

    public List<String> witnesses() {
        return witnesses.stream().map(Witness::name).toList();
    }

    public void collect() {
        if (witnesses.isEmpty()) {
            return;
        }
        Optional<StoredCheckpoint> latest = transparencyLog.latest();
        if (latest.isEmpty()) {
            return;
        }
        for (Witness witness : witnesses) {
            try {
                collect(witness, latest.get());
            } catch (IOException | RuntimeException e) {
                log.warn("Witness {} did not cosign checkpoint {}: {}", witness.name(), latest.get().size(),
                        e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void collect(Witness witness, StoredCheckpoint checkpoint) throws IOException, InterruptedException {
        long old = checkpoints.lastCosignedSize(witness.name()).orElse(0L);
        for (int attempt = 0; attempt < 2 && old < checkpoint.size(); attempt++) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(witness.url())
                    .timeout(timeout)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(request(old, checkpoint), StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                store(witness, checkpoint, response.body());
                return;
            }
            if (response.statusCode() != 409) {
                throw new IllegalStateException("answered " + response.statusCode() + " " + response.body().strip());
            }
            old = Long.parseLong(response.body().strip());
            if (old > checkpoint.size()) {
                throw new IllegalStateException("knows a larger tree of " + old + " entries than this ledger");
            }
        }
    }

    private String request(long old, StoredCheckpoint checkpoint) {
        StringBuilder body = new StringBuilder("old ").append(old).append('\n');
        if (old > 0) {
            for (byte[] hash : transparencyLog.consistencyProof(old, checkpoint.size())) {
                body.append(Base64.getEncoder().encodeToString(hash)).append('\n');
            }
        }
        return body.append('\n').append(checkpoint.note()).toString();
    }

    private void store(Witness witness, StoredCheckpoint checkpoint, String body) {
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String text = line + "\n";
            LogCheckpoint.Note note = LogCheckpoint.parse(checkpoint.note()).with(LogCheckpoint.Signature.parse(text));
            if (note.cosignedBy(witness.key()).isPresent()) {
                checkpoints.addCosignature(new Cosignature(checkpoint.size(), witness.name(), text, clock.instant()));
                return;
            }
        }
        throw new IllegalStateException("returned no valid cosignature");
    }
}
