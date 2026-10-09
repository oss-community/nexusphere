package com.nexusphere.ledger.e2e.mandate;

import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import com.nexusphere.ledger.mandate.MandateCheck;
import com.nexusphere.ledger.mandate.MandateProblem;
import com.nexusphere.ledger.mandate.MandateVerifier;
import com.nexusphere.ledger.mandate.SdJwt;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MandateVerifierE2ETest extends LedgerE2ETestBase {

    private static MandateVerifier verifier(String audience) {
        return MandateVerifier.builder().trustIssuer(ISSUER).audience(audience).cacheTtl(Duration.ZERO).build();
    }

    private JsonNode issue(RegisteredAgent agent, String grantId, String audience) {
        LedgerClient.Response response = agent.client().post("/api/v1/mandates",
                LedgerClient.json(Map.of("grantId", grantId, "audience", audience)));
        if (response.status() != 201) {
            throw new IllegalStateException("Issuing a mandate failed: " + response.body());
        }
        return response.json();
    }

    @Test
    void anotherOrganizationVerifiesAMandateFromThePublicEndpointsOnly() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("a2a/send"), List.of("supplier/*")));
        String token = issue(agent, grantId, "https://supplier.test").path("token").asString();

        MandateCheck check = verifier("https://supplier.test").verify(token, "a2a/send", "supplier/orders");

        assertThat(check.problems()).isEmpty();
        assertThat(check.claims().agentId()).isEqualTo(agent.agentId());
        assertThat(check.claims().issuer()).isEqualTo(ISSUER);
    }

    @Test
    void anAgentCanPresentAMandateWithoutRevealingItsPrincipal() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("a2a/send"), List.of("supplier/*")));
        String token = issue(agent, grantId, "https://supplier.test").path("token").asString();
        String withheld = SdJwt.parse(token).present(Set.of("grant"));
        String tampered = withheld + SdJwt.Disclosure.of("principal", "mallory").encoded() + "~";

        MandateCheck check = verifier("https://supplier.test").verify(withheld, "a2a/send", "supplier/orders");

        assertThat(withheld).doesNotContain(token.substring(token.indexOf('~') + 1));
        assertThat(check.problems()).isEmpty();
        assertThat(check.claims().principalId()).isNull();
        assertThat(check.claims().grantId().toString()).isEqualTo(grantId);
        assertThat(verifier("https://supplier.test").verify(tampered).has(MandateProblem.MALFORMED)).isTrue();
    }

    @Test
    void theVerifierSeesARevocationAsSoonAsTheStatusListIsRefetched() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("*"), List.of("*")));
        JsonNode mandate = issue(agent, grantId, "https://supplier.test");
        MandateVerifier verifier = verifier("https://supplier.test");

        MandateCheck before = verifier.verify(mandate.path("token").asString());
        ledger().post("/api/v1/grants/" + grantId + "/revoke");
        MandateCheck after = verifier.verify(mandate.path("token").asString());

        assertThat(before.valid()).isTrue();
        assertThat(after.has(MandateProblem.REVOKED)).isTrue();
    }

    @Test
    void theVerifierRejectsWhatTheMandateDoesNotAllow() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("a2a/send"), List.of("supplier/*")));
        String token = issue(agent, grantId, "https://supplier.test").path("token").asString();

        assertThat(verifier("https://bank.test").verify(token).has(MandateProblem.WRONG_AUDIENCE)).isTrue();
        assertThat(verifier("https://supplier.test").verify(token, "a2a/send", "bank/transfer")
                .has(MandateProblem.NOT_COVERED)).isTrue();
        assertThat(MandateVerifier.builder().trustIssuer("http://localhost:1").build().verify(token)
                .has(MandateProblem.UNTRUSTED_ISSUER)).isTrue();
    }
}
