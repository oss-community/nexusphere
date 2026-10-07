package com.nexusphere.ledger.chain;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static byte[] bytes(Map<String, ?> object) {
        return write(object).getBytes(StandardCharsets.UTF_8);
    }

    public static String write(Map<String, ?> object) {
        StringBuilder out = new StringBuilder();
        writeValue(out, object);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String text -> writeString(out, text);
            case Long number -> out.append(number);
            case Integer number -> out.append(number);
            case Boolean flag -> out.append(flag);
            case Map<?, ?> map -> writeObject(out, map);
            default -> throw new IllegalArgumentException("Unsupported canonical JSON value: " + value.getClass());
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("Canonical JSON keys must be strings");
            }
            sorted.put(name, value);
        });
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, entry.getKey());
            out.append(':');
            writeValue(out, entry.getValue());
        }
        out.append('}');
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
