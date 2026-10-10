package com.nexusphere.ledger.compliance.application;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.compliance.domain.model.ComplianceProfile;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class Compliance {

    private static final LocalDate REFERENCE = LocalDate.of(2000, 1, 1);

    private final List<ComplianceProfile> profiles;
    private final String region;

    public Compliance(List<ComplianceProfile> profiles, String region) {
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("At least one compliance profile must be active");
        }
        this.profiles = profiles.stream().sorted(Comparator.comparing(ComplianceProfile::id)).toList();
        this.region = region == null || region.isBlank() ? null : region.trim().toLowerCase(Locale.ROOT);
        check();
    }

    public List<ComplianceProfile> profiles() {
        return profiles;
    }

    public String region() {
        return region;
    }

    public List<Checkpoint.Profile> references() {
        return profiles.stream().map(ComplianceProfile::reference).toList();
    }

    public Optional<Period> retention(String action) {
        return profiles.stream().flatMap(profile -> profile.retention().stream())
                .filter(rule -> rule.covers(action))
                .map(ComplianceProfile.Retention::minimum)
                .max(Comparator.comparing(REFERENCE::plus));
    }

    public Optional<Period> maximum(String action) {
        return profiles.stream().flatMap(profile -> profile.retention().stream())
                .filter(rule -> rule.maximum() != null && rule.covers(action))
                .map(ComplianceProfile.Retention::maximum)
                .min(Comparator.comparing(REFERENCE::plus));
    }

    public Optional<Period> shortestMaximum() {
        return profiles.stream().flatMap(profile -> profile.retention().stream())
                .map(ComplianceProfile.Retention::maximum).filter(p -> p != null)
                .min(Comparator.comparing(REFERENCE::plus));
    }

    public List<Rule> retentionRules() {
        Set<String> patterns = new TreeSet<>();
        profiles.forEach(profile -> profile.retention().forEach(rule -> patterns.add(rule.actions())));
        return patterns.stream().map(actions -> {
            String action = example(actions);
            return new Rule(actions, retention(action).orElseThrow().toString(),
                    maximum(action).map(Period::toString).orElse(null));
        }).toList();
    }

    public boolean erasureOnRequest() {
        return profiles.stream().anyMatch(profile -> profile.erasure().onRequest());
    }

    public Optional<Period> erasureDeadline() {
        return profiles.stream().map(profile -> profile.erasure().deadline()).filter(p -> p != null)
                .min(Comparator.comparing(REFERENCE::plus));
    }

    public Optional<Set<String>> residency() {
        return intersection(profiles.stream().map(ComplianceProfile::residency).toList());
    }

    public Optional<Set<String>> timestampAuthorities() {
        return intersection(profiles.stream().map(ComplianceProfile::timestampAuthorities).toList());
    }

    public Map<String, List<String>> requiredFields() {
        Map<String, List<String>> fields = new TreeMap<>();
        profiles.forEach(profile -> profile.requiredFields().forEach(field ->
                fields.computeIfAbsent(field, f -> new ArrayList<>()).add(profile.id())));
        return fields;
    }

    public List<String> reports() {
        return profiles.stream().flatMap(profile -> profile.reports().stream()).distinct().sorted().toList();
    }

    public Map<String, Object> effective() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("retention", retentionRules());
        Map<String, Object> erasure = new LinkedHashMap<>();
        erasure.put("onRequest", erasureOnRequest());
        erasure.put("deadline", erasureDeadline().map(Period::toString).orElse(null));
        map.put("erasure", erasure);
        map.put("residency", residency().orElse(null));
        map.put("requiredFields", requiredFields());
        map.put("timestampAuthorities", timestampAuthorities().orElse(null));
        map.put("reports", reports());
        return map;
    }

    private void check() {
        Optional<Set<String>> residency = residency();
        if (residency.isPresent()) {
            String names = profiles.stream().filter(p -> !p.residency().isEmpty()).map(ComplianceProfile::id)
                    .collect(Collectors.joining(", "));
            if (residency.get().isEmpty()) {
                throw new IllegalStateException("The compliance profiles " + names
                        + " allow no common region for the ledger data");
            }
            if (region == null) {
                throw new IllegalStateException("The compliance profiles " + names + " limit where the ledger data"
                        + " may be kept to " + residency.get() + ": set LEDGER_COMPLIANCE_REGION to the region this"
                        + " ledger and its database run in");
            }
            if (!residency.get().contains(region)) {
                throw new IllegalStateException("The region " + region + " is not allowed by the compliance profiles "
                        + names + ", which allow " + residency.get());
            }
        }
        for (ComplianceProfile profile : profiles) {
            for (ComplianceProfile.Retention rule : profile.retention()) {
                String action = example(rule.actions());
                Optional<Period> maximum = maximum(action);
                Period minimum = retention(action).orElseThrow();
                if (maximum.isPresent() && REFERENCE.plus(maximum.get()).isBefore(REFERENCE.plus(minimum))) {
                    throw new IllegalStateException("The compliance profiles keep " + rule.actions() + " at least "
                            + minimum + " but at most " + maximum.get());
                }
            }
        }
        if (timestampAuthorities().map(Set::isEmpty).orElse(false)) {
            throw new IllegalStateException("The compliance profiles accept no common timestamp authority");
        }
    }

    private static Optional<Set<String>> intersection(List<List<String>> lists) {
        Set<String> common = null;
        for (List<String> list : lists) {
            if (list.isEmpty()) {
                continue;
            }
            if (common == null) {
                common = new TreeSet<>(list);
            } else {
                common.retainAll(new LinkedHashSet<>(list));
            }
        }
        return Optional.ofNullable(common);
    }

    private static String example(String actions) {
        return actions.endsWith("*") ? actions.substring(0, actions.length() - 1) : actions;
    }

    public record Rule(String actions, String minimum, String maximum) {
    }
}
