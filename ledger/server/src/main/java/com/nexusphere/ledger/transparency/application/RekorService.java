package com.nexusphere.ledger.transparency.application;

import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.chain.Signer;
import com.nexusphere.ledger.evidence.domain.repository.CheckpointRepository;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.verifier.RekorEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Service
public class RekorService {

    private static final Logger log = LoggerFactory.getLogger(RekorService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CheckpointRepository checkpoints;
    private final LedgerSigner signer;
    private final Clock clock;
    private final String base;
    private final URI url;
    private final NoteKey key;
    private final Duration timeout;
    private final Signer prehashSigner;
    private final HttpClient http;

    RekorService(CheckpointRepository checkpoints, LedgerSigner signer, LedgerProperties properties, Clock clock) {
        this.checkpoints = checkpoints;
        this.signer = signer;
        this.clock = clock;
        LedgerProperties.Rekor settings = properties.rekor();
        boolean enabled = settings != null && settings.enabled();
        this.timeout = settings == null || settings.timeout() == null ? Duration.ofSeconds(10) : settings.timeout();
        if (!enabled) {
            this.base = null;
            this.url = null;
            this.key = null;
            this.prehashSigner = null;
        } else {
            String trimmed = settings.url().trim();
            this.base = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
            this.url = URI.create(base + "/api/v2/log/entries");
            if (settings.key() == null || settings.key().isBlank()) {
                throw new IllegalStateException("LEDGER_REKOR_KEY must name the verifier key of the Rekor log at "
                        + base);
            }
            this.key = NoteKey.parse(settings.key().trim());
            this.prehashSigner = signer.prehashSigner().orElseThrow(() -> new IllegalStateException(
                    "Rekor entries are signed with Ed25519ph, which the " + signer.providerName()
                            + " signing provider cannot do; use LEDGER_SIGNING_PROVIDER=local or leave "
                            + "LEDGER_REKOR_URL empty"));
        }
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public record Log(String url, String key) {
    }

    public boolean enabled() {
        return url != null;
    }

    public Log log() {
        return new Log(base, key == null ? null : key.vkey());
    }

    public List<NoteKey> keys() {
        return key == null ? List.of() : List.of(key);
    }

    public SignedCheckpoint anchor(SignedCheckpoint signed) {
        if (url == null || signed.rekor() != null) {
            return signed;
        }
        try {
            String entry = submit(signed);
            JsonNode node = JSON.readTree(entry);
            RekorEntry.Verification verification = RekorEntry.verify(node, signed.checkpoint().signedBytes(),
                    signer.publicKey().publicKey(), List.of(key));
            if (!verification.valid()) {
                throw new IllegalStateException(verification.problem());
            }
            checkpoints.appendRekor(signed.checkpoint().sequence(), verification.logIndex(), entry, clock.instant());
            return signed.withRekor(entry);
        } catch (RuntimeException e) {
            log.warn("Anchoring checkpoint {} in Rekor failed: {}", signed.checkpoint().sequence(), e.getMessage());
            return signed;
        }
    }

    public void anchorMissing() {
        if (url == null) {
            return;
        }
        for (SignedCheckpoint signed : checkpoints.withoutRekor(10)) {
            if (!signed.checkpoint().keyId().equals(signer.publicKey().keyId())) {
                continue;
            }
            if (anchor(signed).rekor() == null) {
                return;
            }
        }
    }

    private String submit(SignedCheckpoint signed) {
        byte[] signedBytes = signed.checkpoint().signedBytes();
        String body = RekorEntry.request(signedBytes, prehashSigner.sign(signedBytes), signer.publicKey().publicKey());
        HttpRequest request = HttpRequest.newBuilder(url).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new IllegalStateException("Rekor answered HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
