package com.nexusphere.ledger.demo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class DemoTools {

    private final JsonMapper json;
    private final Map<String, String> files = new ConcurrentHashMap<>(Map.of(
            "/reports/q3.txt", "Revenue grew 12% in Q3.",
            "/invoices/2026-10.txt", "Invoice 2026-10: 4 200 EUR, due 2026-11-01."));
    private final List<String> outbox = new CopyOnWriteArrayList<>();

    DemoTools(JsonMapper json) {
        this.json = json;
    }

    ArrayNode list() {
        ArrayNode tools = json.createArrayNode();
        tools.add(tool("read_file", "Read a file", Map.of("path", "Path of the file"), List.of("path")));
        tools.add(tool("send_email", "Send an email", Map.of("to", "Recipient", "subject", "Subject", "body", "Body"),
                List.of("to", "subject")));
        tools.add(tool("delete_file", "Delete a file", Map.of("path", "Path of the file"), List.of("path")));
        return tools;
    }

    ObjectNode call(String name, JsonNode arguments) {
        return switch (name) {
            case "read_file" -> {
                String content = files.get(arguments.path("path").asString(""));
                yield content == null ? result("No file at " + arguments.path("path").asString(""), true)
                        : result(content, false);
            }
            case "send_email" -> {
                String to = arguments.path("to").asString("");
                if (to.isBlank()) {
                    yield result("A recipient is required.", true);
                }
                outbox.add(to);
                yield result("Sent \"" + arguments.path("subject").asString("") + "\" to " + to + ".", false);
            }
            case "delete_file" -> {
                String removed = files.remove(arguments.path("path").asString(""));
                yield result(removed == null ? "No file to delete." : "Deleted.", removed == null);
            }
            default -> null;
        };
    }

    private ObjectNode tool(String name, String description, Map<String, String> properties, List<String> required) {
        ObjectNode tool = json.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        properties.forEach((key, text) -> props.putObject(key).put("type", "string").put("description", text));
        ArrayNode req = schema.putArray("required");
        required.forEach(req::add);
        return tool;
    }

    private ObjectNode result(String text, boolean error) {
        ObjectNode result = json.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", error);
        return result;
    }
}
