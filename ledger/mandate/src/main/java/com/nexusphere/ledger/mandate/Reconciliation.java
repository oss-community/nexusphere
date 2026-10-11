package com.nexusphere.ledger.mandate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record Reconciliation(String ours, String theirs, Instant from, Instant to, int matched, int undelivered,
                             List<Discrepancy> discrepancies) {

    public static final String NOT_RECEIVED = "NOT_RECEIVED";
    public static final String NOT_SENT = "NOT_SENT";
    public static final String MISMATCH = "MISMATCH";

    private static final Set<Integer> UNDELIVERED = Set.of(401, 502, 503, 504);

    public record Discrepancy(UUID requestId, String kind, String sender, Instant requestedAt, List<String> fields) {
    }

    public boolean reconciled() {
        return discrepancies.isEmpty();
    }

    public static Reconciliation of(ExchangeStatement ours, ExchangeStatement theirs) {
        if (!ours.peer().equals(theirs.issuer()) || !theirs.peer().equals(ours.issuer())) {
            throw new IllegalArgumentException("The statements are not about each other: " + ours.issuer() + " names "
                    + ours.peer() + " and " + theirs.issuer() + " names " + theirs.peer());
        }
        if (!ours.from().equals(theirs.from()) || !ours.to().equals(theirs.to())) {
            throw new IllegalArgumentException("The statements cover different windows");
        }
        int[] counts = new int[2];
        List<Discrepancy> discrepancies = new ArrayList<>();
        compare(ours, theirs, counts, discrepancies);
        compare(theirs, ours, counts, discrepancies);
        discrepancies.sort(Comparator.comparing(Discrepancy::requestedAt).thenComparing(Discrepancy::requestId));
        return new Reconciliation(ours.issuer(), theirs.issuer(), ours.from(), ours.to(), counts[0], counts[1],
                List.copyOf(discrepancies));
    }

    private static void compare(ExchangeStatement sender, ExchangeStatement receiver, int[] counts,
                                List<Discrepancy> discrepancies) {
        Map<UUID, ExchangeStatement.Item> received = new LinkedHashMap<>();
        receiver.exchanges().stream().filter(i -> ExchangeStatement.INBOUND.equals(i.direction()))
                .forEach(i -> received.put(i.requestId(), i));
        Set<UUID> seen = new HashSet<>();
        for (ExchangeStatement.Item sent : sender.exchanges()) {
            if (!ExchangeStatement.OUTBOUND.equals(sent.direction())) {
                continue;
            }
            seen.add(sent.requestId());
            ExchangeStatement.Item in = received.get(sent.requestId());
            if (in == null) {
                if (sent.receiptHash() == null && sent.status() != null && UNDELIVERED.contains(sent.status())) {
                    counts[1]++;
                } else {
                    discrepancies.add(new Discrepancy(sent.requestId(), NOT_RECEIVED, sender.issuer(),
                            sent.requestedAt(), List.of()));
                }
                continue;
            }
            List<String> fields = fields(sent, in);
            if (fields.isEmpty()) {
                counts[0]++;
            } else {
                discrepancies.add(new Discrepancy(sent.requestId(), MISMATCH, sender.issuer(), sent.requestedAt(),
                        fields));
            }
        }
        received.values().stream().filter(i -> !seen.contains(i.requestId())).forEach(i -> discrepancies.add(
                new Discrepancy(i.requestId(), NOT_SENT, sender.issuer(), i.requestedAt(), List.of())));
    }

    private static List<String> fields(ExchangeStatement.Item sent, ExchangeStatement.Item in) {
        List<String> fields = new ArrayList<>();
        if (!sent.requestedAt().equals(in.requestedAt())) {
            fields.add("requestedAt");
        }
        if (!sent.method().equals(in.method())) {
            fields.add("method");
        }
        if (!sent.mandateId().equals(in.mandateId())) {
            fields.add("mandateId");
        }
        if (!sent.requestHash().equals(in.requestHash())) {
            fields.add("requestHash");
        }
        if (both(sent.status(), in.status()) && !sent.status().equals(in.status())) {
            fields.add("status");
        }
        if (both(sent.responseHash(), in.responseHash()) && !sent.responseHash().equals(in.responseHash())) {
            fields.add("responseHash");
        }
        if (both(sent.receiptHash(), in.receiptHash())) {
            if (!sent.receiptHash().equals(in.receiptHash())) {
                fields.add("receiptHash");
            } else if (!Objects.equals(sent.outcome(), in.outcome())) {
                fields.add("outcome");
            }
        }
        return fields;
    }

    private static boolean both(Object a, Object b) {
        return a != null && b != null;
    }
}
