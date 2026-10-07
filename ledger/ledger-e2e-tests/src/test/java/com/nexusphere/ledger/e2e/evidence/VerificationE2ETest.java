package com.nexusphere.ledger.e2e.evidence;

import com.nexusphere.ledger.chain.ChainVerification;
import com.nexusphere.ledger.chain.ChainVerifier;
import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationE2ETest extends LedgerE2ETestBase {

    @Test
    void theLedgerVerifiesItsOwnChainAndCheckpoints() {
        LedgerClient ledger = ledger();
        ledger.recordEvidence(toolCall(unique("agent"), "erin", "search"));
        ledger.post("/api/v1/checkpoints");
        ledger.recordEvidence(toolCall(unique("agent"), "erin", "summarize"));

        JsonNode report = ledger.get("/api/v1/verification").json();
        JsonNode head = ledger.get("/api/v1/ledger/head").json();

        assertThat(report.path("valid").asBoolean()).isTrue();
        assertThat(report.path("checkedCheckpoints").asLong()).isPositive();
        assertThat(report.path("headSequence").asLong()).isEqualTo(head.path("sequence").asLong());
        assertThat(report.path("headHash").asString()).isEqualTo(head.path("hash").asString());
    }

    @Test
    void aCheckpointCanBeVerifiedOfflineWithThePublishedKey() {
        LedgerClient ledger = ledger();
        ledger.recordEvidence(toolCall(unique("agent"), "frank", "deploy"));

        LedgerClient.Response created = ledger.post("/api/v1/checkpoints");
        JsonNode checkpoint = created.json();
        JsonNode key = ledger.get("/api/v1/keys").json().get(0);
        SignedCheckpoint signed = new SignedCheckpoint(new Checkpoint(checkpoint.path("sequence").asLong(),
                checkpoint.path("headHash").asString(), Instant.parse(checkpoint.path("createdAt").asString()),
                checkpoint.path("keyId").asString()), checkpoint.path("signature").asString());
        SigningKeys.PublicKeyInfo publicKey =
                SigningKeys.PublicKeyInfo.of(SigningKeys.decodePublic(key.path("publicKey").asString()));

        assertThat(created.status()).isEqualTo(200);
        assertThat(key.path("algorithm").asString()).isEqualTo("Ed25519");
        assertThat(publicKey.keyId()).isEqualTo(key.path("keyId").asString());
        assertThat(signed.verify(publicKey)).isTrue();
        assertThat(ledger.get("/api/v1/checkpoints/latest").json().path("sequence").asLong())
                .isGreaterThanOrEqualTo(checkpoint.path("sequence").asLong());
    }

    @Test
    void anOutsiderCanRebuildAndVerifyTheWholeChainFromTheApi() {
        LedgerClient ledger = ledger();
        for (int i = 0; i < 3; i++) {
            ledger.recordEvidence(toolCall(unique("agent"), "grace", "step-" + i));
        }
        JsonNode checkpoint = ledger.post("/api/v1/checkpoints").json();

        List<EvidenceEntry> entries = new ArrayList<>();
        long after = 0;
        while (true) {
            JsonNode page = ledger.get("/api/v1/evidence?limit=2&after=" + after).json();
            page.path("items").forEach(item -> entries.add(LedgerClient.toEntry(item)));
            if (page.path("nextAfter").isNull()) {
                break;
            }
            after = page.path("nextAfter").asLong();
        }
        ChainVerification result = ChainVerifier.verify(entries);
        EvidenceEntry atCheckpoint = entries.stream()
                .filter(e -> e.sequence() == checkpoint.path("sequence").asLong()).findFirst().orElseThrow();

        assertThat(result.valid()).isTrue();
        assertThat(atCheckpoint.hash()).isEqualTo(checkpoint.path("headHash").asString());
    }
}
