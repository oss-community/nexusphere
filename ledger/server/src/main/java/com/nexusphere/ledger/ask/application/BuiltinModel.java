package com.nexusphere.ledger.ask.application;

import com.nexusphere.ledger.chain.EvidenceEntry;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class BuiltinModel implements LanguageModel {

    public static final String NAME = "builtin";
    public static final String NO_EVIDENCE = "No verified evidence matches the question.";

    private static final Pattern PRINCIPAL = Pattern.compile(
            "\\b(?:for|about|on behalf of|of principal|principal)\\s+([\\p{L}\\p{N}][\\p{L}\\p{N}._@-]*)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET = Pattern.compile("\\b([a-z0-9-]+(?:[/_][a-z0-9_-]+)+)\\b");
    private static final Pattern LAST = Pattern.compile("\\b(?:last|past|previous)\\s+(\\d{1,3})\\s+(day|days|hour|hours|week|weeks)\\b");
    private static final Pattern DATE = Pattern.compile("\\b(since|on|after|before)\\s+(\\d{4}-\\d{2}-\\d{2})\\b");
    private static final Set<String> NOT_PRINCIPALS = Set.of("the", "a", "an", "me", "my", "him", "her", "them", "us",
            "it", "this", "that", "which", "what", "whom", "all", "any", "each", "every", "agent", "agents", "today",
            "yesterday", "week", "month", "last");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public QueryPlan plan(String question, PlanContext context) {
        String q = question.toLowerCase(Locale.ROOT);
        String agent = longest(q, context.agents());
        String action = longest(q, context.actions());
        String principal = null;
        Matcher named = PRINCIPAL.matcher(question);
        while (named.find()) {
            String candidate = named.group(1).replaceAll("[.,;:!?]+$", "");
            if (!NOT_PRINCIPALS.contains(candidate.toLowerCase(Locale.ROOT))
                    && !candidate.equalsIgnoreCase(agent)) {
                principal = candidate;
                break;
            }
        }
        String target = null;
        Matcher targets = TARGET.matcher(q);
        while (targets.find()) {
            String candidate = targets.group(1);
            if (!candidate.equals(agent) && !candidate.equals(action) && !candidate.matches("\\d{4}-\\d{2}-\\d{2}")
                    && (action == null || !action.contains(candidate))) {
                target = candidate;
                break;
            }
        }
        String decision = null;
        if (q.matches("(?s).*\\b(denied|deny|denials|blocked|refused|rejected|not allowed)\\b.*")) {
            decision = "DENY";
        } else if (q.matches("(?s).*\\b(allowed|permitted|approved)\\b.*")) {
            decision = "ALLOW";
        }
        String outcome = null;
        if (q.matches("(?s).*\\b(failed|failures?|errors?|went wrong)\\b.*")) {
            outcome = "FAILED";
        } else if (q.matches("(?s).*\\b(succeeded|successful|successfully)\\b.*")) {
            outcome = "SUCCEEDED";
        } else if (q.matches("(?s).*\\bpending\\b.*")) {
            outcome = "PENDING";
        }
        Instant[] window = window(q, context.now());
        return new QueryPlan(agent, principal, action, target, decision, outcome, window[0], window[1]);
    }

    static Instant[] window(String q, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Matcher last = LAST.matcher(q);
        if (last.find()) {
            long amount = Long.parseLong(last.group(1));
            String unit = last.group(2);
            if (unit.startsWith("hour")) {
                return new Instant[]{now.minus(amount, ChronoUnit.HOURS), null};
            }
            long days = unit.startsWith("week") ? amount * 7 : amount;
            return new Instant[]{start(today.minusDays(days - 1)), null};
        }
        Matcher date = DATE.matcher(q);
        if (date.find()) {
            try {
                LocalDate day = LocalDate.parse(date.group(2));
                return switch (date.group(1)) {
                    case "on" -> new Instant[]{start(day), start(day.plusDays(1))};
                    case "before" -> new Instant[]{null, start(day)};
                    case "after" -> new Instant[]{start(day.plusDays(1)), null};
                    default -> new Instant[]{start(day), null};
                };
            } catch (DateTimeParseException e) {
                return new Instant[]{null, null};
            }
        }
        if (q.contains("yesterday")) {
            return new Instant[]{start(today.minusDays(1)), start(today)};
        }
        if (q.contains("today")) {
            return new Instant[]{start(today), null};
        }
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (q.contains("last week") || q.contains("previous week")) {
            return new Instant[]{start(monday.minusWeeks(1)), start(monday)};
        }
        if (q.contains("this week")) {
            return new Instant[]{start(monday), null};
        }
        LocalDate first = today.withDayOfMonth(1);
        if (q.contains("last month") || q.contains("previous month")) {
            return new Instant[]{start(first.minusMonths(1)), start(first)};
        }
        if (q.contains("this month")) {
            return new Instant[]{start(first), null};
        }
        return new Instant[]{null, null};
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static String longest(String q, List<String> candidates) {
        return candidates.stream().filter(c -> !c.isBlank() && contains(q, c.toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(String::length)).orElse(null);
    }

    private static boolean contains(String q, String word) {
        int at = q.indexOf(word);
        while (at >= 0) {
            boolean before = at == 0 || !Character.isLetterOrDigit(q.charAt(at - 1));
            int end = at + word.length();
            boolean after = end >= q.length() || !Character.isLetterOrDigit(q.charAt(end));
            if (before && after) {
                return true;
            }
            at = q.indexOf(word, at + 1);
        }
        return false;
    }

    @Override
    public String answer(String question, QueryPlan plan, List<EvidenceEntry> evidence) {
        if (evidence.isEmpty()) {
            return NO_EVIDENCE;
        }
        List<EvidenceEntry> sorted = evidence.stream().sorted(Comparator.comparingLong(EvidenceEntry::sequence))
                .toList();
        EvidenceEntry first = sorted.getFirst();
        EvidenceEntry last = sorted.getLast();
        List<String> sentences = new ArrayList<>();
        sentences.add(sorted.size() + (sorted.size() == 1 ? " verified entry matches" : " verified entries match")
                + " the question, from " + day(first) + " to " + day(last) + " " + Citations.cite(first.sequence())
                + (first == last ? "" : Citations.cite(last.sequence())) + ".");
        Map<String, List<EvidenceEntry>> groups = sorted.stream().collect(Collectors.groupingBy(
                entry -> day(entry) + "|" + entry.agentId() + "|" + entry.action() + "|" + entry.target() + "|"
                        + entry.principalId() + "|" + entry.decision() + "|" + entry.outcome(),
                LinkedHashMap::new, Collectors.toList()));
        groups.values().forEach(group -> sentences.add(describe(group)));
        return String.join("\n", sentences);
    }

    private static String describe(List<EvidenceEntry> group) {
        EvidenceEntry entry = group.getFirst();
        StringBuilder sentence = new StringBuilder("On ").append(day(entry)).append(", ").append(entry.agentId())
                .append(" recorded ").append(entry.action());
        if (entry.target() != null) {
            sentence.append(" on ").append(entry.target());
        }
        if (entry.principalId() != null) {
            sentence.append(" for ").append(entry.principalId());
        } else if (entry.erased()) {
            sentence.append(" for an erased principal");
        }
        sentence.append(group.size() == 1 ? " once" : " " + group.size() + " times");
        List<String> states = new ArrayList<>();
        if ("DENY".equals(entry.decision())) {
            states.add("denied");
        } else if ("ALLOW".equals(entry.decision())) {
            states.add("allowed");
        }
        if (entry.outcome() != null && !"DENIED".equals(entry.outcome())) {
            states.add(entry.outcome().toLowerCase(Locale.ROOT));
        }
        if (!states.isEmpty()) {
            sentence.append(", ").append(String.join(" and ", states));
        }
        sentence.append(" ");
        group.forEach(item -> sentence.append(Citations.cite(item.sequence())));
        return sentence.append(".").toString();
    }

    private static String day(EvidenceEntry entry) {
        return LocalDate.ofInstant(entry.occurredAt(), ZoneOffset.UTC).toString();
    }
}
