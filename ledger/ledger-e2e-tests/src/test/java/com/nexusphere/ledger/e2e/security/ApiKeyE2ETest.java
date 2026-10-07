package com.nexusphere.ledger.e2e.security;

import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyE2ETest extends LedgerE2ETestBase {

    @Test
    void theApiRequiresTheLedgerKey() {
        LedgerClient.Response missing = anonymous().get("/api/v1/ledger/head");
        LedgerClient.Response wrong = ledger().withApiKey("wrong-key").post("/api/v1/evidence",
                LedgerClient.json(toolCall("agent", "mallory", "search")));

        assertThat(missing.status()).isEqualTo(401);
        assertThat(missing.json().path("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(missing.header("WWW-Authenticate")).hasValue("Bearer");
        assertThat(wrong.status()).isEqualTo(401);
    }

    @Test
    void healthIsOpenForProbes() {
        assertThat(anonymous().get("/actuator/health").status()).isEqualTo(200);
    }
}
