package com.nexusphere.ledger.e2e.evidence;

import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceE2ETest extends LedgerE2ETestBase {

    @Test
    void recordedEvidenceIsSealedAndLinkedToThePreviousEntry() {
        LedgerClient ledger = ledger();
        String agent = unique("agent");

        LedgerClient.Response created = ledger.post("/api/v1/evidence",
                LedgerClient.json(toolCall(agent, "alice", "read_file")));
        JsonNode first = created.json();
        JsonNode second = ledger.recordEvidence(toolCall(agent, "alice", "write_file"));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("Location")).hasValue("/api/v1/evidence/" + first.path("id").asString());
        assertThat(first.path("format").asString()).isEqualTo("nexusphere-ledger/evidence/v1");
        assertThat(first.path("hash").asString()).hasSize(64);
        assertThat(second.path("sequence").asLong()).isGreaterThan(first.path("sequence").asLong());
        assertThat(LedgerClient.toEntry(first).computeHash()).isEqualTo(first.path("hash").asString());
        assertThat(LedgerClient.toEntry(second).computeHash()).isEqualTo(second.path("hash").asString());
    }

    @Test
    void evidenceCanBeReadBackExactlyAsRecorded() {
        LedgerClient ledger = ledger();
        Map<String, Object> body = toolCall(unique("agent"), "bob", "send_email");
        body.put("reason", "Monthly invoice reminder");
        body.put("delegationId", "grant-42");
        body.put("correlationId", "conversation-7");
        body.put("inputHash", "a".repeat(64));
        body.put("occurredAt", "2026-10-01T08:00:00.123456Z");
        JsonNode recorded = ledger.recordEvidence(body);

        LedgerClient.Response read = ledger.get("/api/v1/evidence/" + recorded.path("id").asString());

        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json()).isEqualTo(recorded);
        assertThat(read.json().path("occurredAt").asString()).isEqualTo("2026-10-01T08:00:00.123456Z");
        assertThat(read.json().path("attributes").path("tool").asString()).isEqualTo("send_email");
    }

    @Test
    void evidenceIsListedPerAgentAndPrincipalInPages() {
        LedgerClient ledger = ledger();
        String agent = unique("agent");
        String principal = unique("principal");
        for (int i = 0; i < 5; i++) {
            ledger.recordEvidence(toolCall(agent, principal, "tool-" + i));
        }
        ledger.recordEvidence(toolCall(unique("other-agent"), principal, "tool-x"));

        JsonNode firstPage = ledger.get("/api/v1/evidence?agentId=" + agent + "&limit=3").json();
        long nextAfter = firstPage.path("nextAfter").asLong();
        JsonNode secondPage = ledger.get("/api/v1/evidence?agentId=" + agent + "&limit=3&after=" + nextAfter).json();
        JsonNode byPrincipal = ledger.get("/api/v1/evidence?principalId=" + principal).json();

        assertThat(firstPage.path("items").size()).isEqualTo(3);
        assertThat(secondPage.path("items").size()).isEqualTo(2);
        assertThat(secondPage.path("nextAfter").isNull()).isTrue();
        assertThat(byPrincipal.path("items").size()).isEqualTo(6);
    }

    @Test
    void aDeniedActionIsRecordedWithItsReason() {
        Map<String, Object> body = toolCall(unique("agent"), "carol", "delete_repository");
        body.put("decision", "DENY");
        body.put("outcome", "DENIED");
        body.put("reason", "No grant covers delete_repository");

        JsonNode recorded = ledger().recordEvidence(body);

        assertThat(recorded.path("decision").asString()).isEqualTo("DENY");
        assertThat(recorded.path("outcome").asString()).isEqualTo("DENIED");
        assertThat(recorded.path("reason").asString()).isEqualTo("No grant covers delete_repository");
    }

    @Test
    void invalidEvidenceIsRejectedWithFieldErrors() {
        Map<String, Object> body = toolCall(unique("agent"), "dave", "search");
        body.remove("principalId");
        body.put("decision", "DENY");
        body.put("inputHash", "not-a-hash");

        LedgerClient.Response response = ledger().post("/api/v1/evidence", LedgerClient.json(body));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(response.json().path("details").path("fields").has("principalId")).isTrue();
        assertThat(response.json().path("details").path("fields").has("outcome")).isTrue();
        assertThat(response.json().path("details").path("fields").has("inputHash")).isTrue();
    }

    @Test
    void malformedRequestsAndUnknownEvidenceAreReportedClearly() {
        LedgerClient ledger = ledger();

        LedgerClient.Response badEnum = ledger.post("/api/v1/evidence",
                "{\"agentId\":\"a\",\"principalId\":\"p\",\"action\":\"x\",\"outcome\":\"MAYBE\"}");
        LedgerClient.Response unknown = ledger.get("/api/v1/evidence/00000000-0000-0000-0000-000000000000");
        LedgerClient.Response badLimit = ledger.get("/api/v1/evidence?limit=0");

        assertThat(badEnum.status()).isEqualTo(400);
        assertThat(badEnum.json().path("code").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(badLimit.status()).isEqualTo(400);
    }
}
