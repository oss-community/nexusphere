package com.nexusphere.ledger.server.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class EventStream {

    public static final String CONTENT_TYPE = "text/event-stream";

    public record Event(String name, String data, List<String> lines) {
    }

    @FunctionalInterface
    public interface Listener {
        boolean relay(Event event) throws IOException;
    }

    private EventStream() {
    }

    public static boolean isEventStream(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith(CONTENT_TYPE);
    }

    public static void relay(InputStream in, OutputStream out, Listener listener) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                dispatch(lines, out, listener);
                lines.clear();
            } else {
                lines.add(line);
            }
        }
        dispatch(lines, out, listener);
    }

    public static void write(OutputStream out, String name, String data) throws IOException {
        StringBuilder event = new StringBuilder();
        if (name != null) {
            event.append("event: ").append(name).append('\n');
        }
        for (String part : data.split("\n", -1)) {
            event.append("data: ").append(part).append('\n');
        }
        out.write(event.append('\n').toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void dispatch(List<String> lines, OutputStream out, Listener listener) throws IOException {
        if (lines.isEmpty()) {
            return;
        }
        String name = null;
        StringBuilder data = null;
        for (String line : lines) {
            if (line.startsWith("event:")) {
                name = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                value = value.startsWith(" ") ? value.substring(1) : value;
                data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
            }
        }
        Event event = new Event(name, data == null ? null : data.toString(), List.copyOf(lines));
        if (listener.relay(event) && out != null) {
            StringBuilder raw = new StringBuilder();
            for (String line : lines) {
                raw.append(line).append('\n');
            }
            out.write(raw.append('\n').toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
