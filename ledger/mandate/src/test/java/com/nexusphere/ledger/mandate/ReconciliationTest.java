package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.Signer;
import com.nexusphere.ledger.chain.SigningKeys;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationTest {

    private static final String ACME = "https://ledger.acme.test";
    private static final String SUPPLIER = "https://ledger.supplier.test";
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-02T00:00:00Z");
    private static final UUID MANDATE = UUID.randomUUID();
    private static final KeyPair KEYS = SigningKeys.generate();

    private static String hash(String text) {
        return Hashes.sha256(text.getBytes());
    }

    private static ExchangeStatement.Item item(String direction, UUID id, String response, Integer status,
                                               String receipt) {
        return new ExchangeStatement.Item(direction, id, FROM.plusSeconds(60), "message/send", MANDATE,
                hash("request " + id), response == null ? null : hash(response), status,
                status == null ? null : status == 200 ? "SUCCEEDED" : "FAILED", receipt == null ? null : hash(receipt));
    }

    private static ExchangeStatement statement(String issuer, String peer, List<ExchangeStatement.Item> items) {
        return new ExchangeStatement(issuer, peer, FROM, TO, TO.plusSeconds(10), items);
    }

    @Test
    void matchingExchangesInBothDirectionsReconcile() {
        UUID out = UUID.randomUUID();
        UUID back = UUID.randomUUID();
        ExchangeStatement acme = statement(ACME, SUPPLIER, List.of(
                item("OUTBOUND", out, "ok", 200, "receipt 1"), item("INBOUND", back, "fine", 200, "receipt 2")));
        ExchangeStatement supplier = statement(SUPPLIER, ACME, List.of(
                item("INBOUND", out, "ok", 200, "receipt 1"), item("OUTBOUND", back, "fine", 200, "receipt 2")));

        Reconciliation result = Reconciliation.of(acme, supplier);

        assertThat(result.reconciled()).isTrue();
        assertThat(result.matched()).isEqualTo(2);
    }

    @Test
    void everyKindOfDifferenceIsReported() {
        UUID missing = UUID.randomUUID();
        UUID refused = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        UUID changed = UUID.randomUUID();
        ExchangeStatement acme = statement(ACME, SUPPLIER, List.of(item("OUTBOUND", missing, "ok", 200, "r1"),
                item("OUTBOUND", refused, "no", 401, null), item("OUTBOUND", changed, "ok", 200, "r3")));
        ExchangeStatement supplier = statement(SUPPLIER, ACME, List.of(item("INBOUND", unknown, "ok", 200, "r2"),
                item("INBOUND", changed, "other", 200, "r3")));

        Reconciliation result = Reconciliation.of(acme, supplier);

        assertThat(result.undelivered()).isEqualTo(1);
        assertThat(result.matched()).isZero();
        assertThat(result.discrepancies()).extracting(Reconciliation.Discrepancy::kind)
                .containsExactlyInAnyOrder(Reconciliation.NOT_RECEIVED, Reconciliation.NOT_SENT,
                        Reconciliation.MISMATCH);
        assertThat(result.discrepancies()).filteredOn(d -> d.requestId().equals(changed)).singleElement()
                .extracting(Reconciliation.Discrepancy::fields).isEqualTo(List.of("responseHash"));
        assertThat(result.discrepancies()).filteredOn(d -> d.requestId().equals(unknown)).singleElement()
                .extracting(Reconciliation.Discrepancy::sender).isEqualTo(ACME);
    }

    @Test
    void statementsMustBeAboutEachOtherAndTheSameWindow() {
        ExchangeStatement acme = statement(ACME, SUPPLIER, List.of());

        assertThatThrownBy(() -> Reconciliation.of(acme, statement(SUPPLIER, "https://other.test", List.of())))
                .hasMessageContaining("not about each other");
        assertThatThrownBy(() -> Reconciliation.of(acme, new ExchangeStatement(SUPPLIER, ACME, FROM,
                TO.plusSeconds(1), TO, List.of()))).hasMessage("The statements cover different windows");
    }

    @Test
    void aSignedStatementVerifiesWithTheIssuerKeyOnly() {
        List<ExchangeStatement.Item> items = new ArrayList<>();
        items.add(item("OUTBOUND", UUID.randomUUID(), null, null, null));
        ExchangeStatement acme = statement(ACME, SUPPLIER, items);
        String token = acme.sign(SigningKeys.keyIdOf(KEYS.getPublic()), Signer.of(KEYS.getPrivate()));

        ExchangeStatement verified = ExchangeStatement.verified(token, ACME, KeyResolver.fixed(ACME, KEYS.getPublic()));

        assertThat(verified).isEqualTo(acme);
        assertThatThrownBy(() -> ExchangeStatement.verified(token, ACME,
                KeyResolver.fixed(ACME, SigningKeys.generate().getPublic()))).isInstanceOf(IllegalArgumentException.class);
    }
}
