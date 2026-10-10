package com.nexusphere.ledger.transparency.application;

import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.chain.TimestampToken;
import com.nexusphere.ledger.compliance.application.Compliance;
import com.nexusphere.ledger.evidence.domain.repository.CheckpointRepository;
import com.nexusphere.ledger.server.config.LedgerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;

@Service
public class TimestampService {

    private static final Logger log = LoggerFactory.getLogger(TimestampService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CheckpointRepository checkpoints;
    private final Clock clock;
    private final URI url;
    private final List<X509Certificate> certificates;
    private final Duration timeout;
    private final HttpClient http;

    TimestampService(CheckpointRepository checkpoints, LedgerProperties properties, Compliance compliance,
                     Clock clock) {
        this.checkpoints = checkpoints;
        this.clock = clock;
        LedgerProperties.Timestamp settings = properties.timestamp();
        boolean enabled = settings != null && settings.enabled();
        this.url = enabled ? URI.create(settings.url().trim()) : null;
        this.timeout = settings == null || settings.timeout() == null ? Duration.ofSeconds(10) : settings.timeout();
        this.certificates = enabled && settings.certificates() != null && !settings.certificates().isBlank()
                ? read(Path.of(settings.certificates().trim())) : List.of();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        check(compliance);
    }

    public record Authority(String url, List<String> certificates) {
    }

    public boolean enabled() {
        return url != null;
    }

    public Authority authority() {
        return new Authority(url == null ? null : url.toString(), certificates.stream().map(TimestampService::pem)
                .toList());
    }

    public SignedCheckpoint stamp(SignedCheckpoint signed) {
        if (url == null || signed.timestamp() != null) {
            return signed;
        }
        try {
            TimestampToken token = request(signed.timestampImprint());
            checkpoints.appendTimestamp(signed.checkpoint().sequence(), token.encoded(), token.genTime(),
                    clock.instant());
            return signed.withTimestamp(token.base64());
        } catch (RuntimeException e) {
            log.warn("Timestamping checkpoint {} failed: {}", signed.checkpoint().sequence(), e.getMessage());
            return signed;
        }
    }

    public void stampMissing() {
        if (url == null) {
            return;
        }
        for (SignedCheckpoint signed : checkpoints.withoutTimestamp(10)) {
            if (stamp(signed).timestamp() == null) {
                return;
            }
        }
    }

    TimestampToken request(byte[] imprint) {
        BigInteger nonce = new BigInteger(63, RANDOM);
        HttpRequest request = HttpRequest.newBuilder(url).timeout(timeout)
                .header("Content-Type", "application/timestamp-query")
                .POST(HttpRequest.BodyPublishers.ofByteArray(TimestampToken.request(imprint, nonce))).build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("the timestamp authority answered HTTP " + response.statusCode());
        }
        TimestampToken token = TimestampToken.fromResponse(response.body());
        if (!nonce.equals(token.nonce())) {
            throw new IllegalStateException("the timestamp answers a different request");
        }
        TimestampToken.Verification verification = token.verify(imprint, certificates);
        if (!verification.valid()) {
            throw new IllegalStateException(verification.problem());
        }
        return token;
    }

    private void check(Compliance compliance) {
        Set<String> accepted = compliance.timestampAuthorities().orElse(null);
        if (accepted == null) {
            return;
        }
        if (url == null) {
            throw new IllegalStateException("The compliance profiles require a timestamp authority: set "
                    + "LEDGER_TIMESTAMP_URL to one of " + accepted);
        }
        if (!accepted.contains(url.toString())) {
            throw new IllegalStateException("The timestamp authority " + url + " is not accepted by the compliance "
                    + "profiles, which accept " + accepted);
        }
    }

    private static List<X509Certificate> read(Path file) {
        try {
            return TimestampToken.readCertificates(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException("The timestamp authority certificates cannot be read from " + file, e);
        }
    }

    private static String pem(X509Certificate certificate) {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
