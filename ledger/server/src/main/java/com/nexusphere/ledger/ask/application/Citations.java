package com.nexusphere.ledger.ask.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Citations {

    public record Sentence(String text, List<Long> citations) {
    }

    public record Parsed(List<Sentence> sentences, int dropped) {
    }

    private static final Pattern GROUP = Pattern.compile("\\[\\s*#\\s*\\d+(?:\\s*[,;]\\s*#?\\s*\\d+)*\\s*]");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private Citations() {
    }

    public static String cite(long sequence) {
        return "[#" + sequence + "]";
    }

    public static Parsed parse(String answer, Set<Long> allowed) {
        List<Sentence> sentences = new ArrayList<>();
        int dropped = 0;
        for (String raw : split(answer == null ? "" : answer)) {
            Set<Long> cited = new LinkedHashSet<>();
            Matcher group = GROUP.matcher(raw);
            while (group.find()) {
                Matcher number = NUMBER.matcher(group.group());
                while (number.find()) {
                    cited.add(Long.parseLong(number.group()));
                }
            }
            String text = GROUP.matcher(raw).replaceAll("").replaceAll("\\s+([.!?,;:])", "$1")
                    .replaceAll("\\s{2,}", " ").strip();
            if (text.isEmpty()) {
                continue;
            }
            if (cited.isEmpty() || !allowed.containsAll(cited)) {
                dropped++;
            } else {
                sentences.add(new Sentence(text, List.copyOf(cited)));
            }
        }
        return new Parsed(List.copyOf(sentences), dropped);
    }

    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                flush(current, parts);
                continue;
            }
            current.append(c);
            if (c == '.' || c == '!' || c == '?') {
                int j = i + 1;
                while (j < n && (text.charAt(j) == '.' || text.charAt(j) == '!' || text.charAt(j) == '?')) {
                    current.append(text.charAt(j++));
                }
                int k = j;
                StringBuilder trailing = new StringBuilder();
                while (true) {
                    int s = k;
                    while (s < n && text.charAt(s) == ' ') {
                        s++;
                    }
                    Matcher group = GROUP.matcher(text).region(s, n);
                    if (s < n && group.lookingAt()) {
                        trailing.append(text, k, group.end());
                        k = group.end();
                    } else {
                        break;
                    }
                }
                if (k >= n || Character.isWhitespace(text.charAt(k))) {
                    current.append(trailing);
                    flush(current, parts);
                    i = k - 1;
                } else {
                    i = j - 1;
                }
            }
        }
        flush(current, parts);
        return parts;
    }

    private static void flush(StringBuilder current, List<String> parts) {
        if (!current.toString().isBlank()) {
            parts.add(current.toString().strip());
        }
        current.setLength(0);
    }
}
