package com.nexusphere.capability.domain.model;

import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.ValidationException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CapabilitySchema {

    private static final Set<String> TYPES = Set.of("object", "array", "string", "number", "integer", "boolean");
    private static final Set<String> KEYWORDS = Set.of("type", "title", "description", "properties", "required",
            "additionalProperties", "items", "enum");

    private record Node(String type, Map<String, Node> properties, List<String> required, boolean additionalProperties,
                        Node items, List<Object> allowed) {
    }

    private final Map<String, Object> document;
    private final Node root;

    private CapabilitySchema(Map<String, Object> document, Node root) {
        this.document = document;
        this.root = root;
    }

    public static CapabilitySchema of(Map<String, Object> document) {
        if (document == null || document.isEmpty()) {
            throw invalidSchema("The schema must not be empty");
        }
        Node root = parse(document, "$");
        if (!root.type().equals("object")) {
            throw invalidSchema("The schema root must be of type object");
        }
        return new CapabilitySchema(Collections.unmodifiableMap(new LinkedHashMap<>(document)), root);
    }

    public Map<String, Object> document() {
        return document;
    }

    public List<String> violations(Map<String, Object> specification) {
        List<String> violations = new ArrayList<>();
        check(root, specification, "$", violations);
        return violations;
    }

    public void validate(Map<String, Object> specification) {
        List<String> violations = violations(specification);
        if (!violations.isEmpty()) {
            throw new DomainException(ErrorCategory.VALIDATION_ERROR, "CAPABILITY_SPECIFICATION_INVALID",
                    "The specification does not match the capability type schema", Map.of("violations", violations));
        }
    }

    private static Node parse(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalidSchema(path + " must be a schema object");
        }
        for (Object keyword : map.keySet()) {
            if (!KEYWORDS.contains(String.valueOf(keyword))) {
                throw invalidSchema(path + " uses the unsupported keyword " + keyword);
            }
        }
        if (!(map.get("type") instanceof String type) || !TYPES.contains(type)) {
            throw invalidSchema(path + ".type must be one of " + String.join(", ", TYPES.stream().sorted().toList()));
        }
        Map<String, Node> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        boolean additional = true;
        Node items = null;
        if (type.equals("object")) {
            Object declared = map.get("properties");
            if (declared != null) {
                if (!(declared instanceof Map<?, ?> fields)) {
                    throw invalidSchema(path + ".properties must be an object");
                }
                fields.forEach((name, field) -> properties.put(String.valueOf(name), parse(field, path + "." + name)));
            }
            Object names = map.get("required");
            if (names != null) {
                if (!(names instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
                    throw invalidSchema(path + ".required must be a list of property names");
                }
                for (Object name : list) {
                    if (!properties.containsKey((String) name)) {
                        throw invalidSchema(path + ".required names the undeclared property " + name);
                    }
                    required.add((String) name);
                }
            }
            Object open = map.get("additionalProperties");
            if (open != null) {
                if (!(open instanceof Boolean flag)) {
                    throw invalidSchema(path + ".additionalProperties must be a boolean");
                }
                additional = flag;
            }
        } else if (map.containsKey("properties") || map.containsKey("required") || map.containsKey("additionalProperties")) {
            throw invalidSchema(path + " declares object keywords on type " + type);
        }
        if (type.equals("array")) {
            if (map.containsKey("items")) {
                items = parse(map.get("items"), path + "[]");
            }
        } else if (map.containsKey("items")) {
            throw invalidSchema(path + " declares items on type " + type);
        }
        List<Object> allowed = null;
        if (map.containsKey("enum")) {
            if (!(map.get("enum") instanceof List<?> values) || values.isEmpty()) {
                throw invalidSchema(path + ".enum must be a non-empty list");
            }
            allowed = List.copyOf(values);
            for (Object candidate : allowed) {
                if (!matchesType(type, candidate)) {
                    throw invalidSchema(path + ".enum value " + candidate + " is not of type " + type);
                }
            }
        }
        return new Node(type, Map.copyOf(properties), List.copyOf(required), additional, items, allowed);
    }

    private static void check(Node node, Object value, String path, List<String> violations) {
        if (!matchesType(node.type(), value)) {
            violations.add(path + " must be of type " + node.type());
            return;
        }
        if (node.allowed() != null && node.allowed().stream().noneMatch(candidate -> sameValue(candidate, value))) {
            violations.add(path + " must be one of " + node.allowed());
        }
        if (value instanceof Map<?, ?> object) {
            for (String name : node.required()) {
                if (!object.containsKey(name)) {
                    violations.add(path + "." + name + " is required");
                }
            }
            object.forEach((name, field) -> {
                Node declared = node.properties().get(String.valueOf(name));
                if (declared != null) {
                    check(declared, field, path + "." + name, violations);
                } else if (!node.additionalProperties()) {
                    violations.add(path + "." + name + " is not allowed");
                }
            });
        }
        if (value instanceof List<?> list && node.items() != null) {
            for (int i = 0; i < list.size(); i++) {
                check(node.items(), list.get(i), path + "[" + i + "]", violations);
            }
        }
    }

    private static boolean matchesType(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "integer" -> isInteger(value);
            default -> false;
        };
    }

    private static boolean isInteger(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger) {
            return true;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().scale() <= 0;
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) && number == Math.rint(number);
        }
        return false;
    }

    private static boolean sameValue(Object candidate, Object value) {
        if (candidate instanceof Number left && value instanceof Number right) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        }
        return Objects.equals(candidate, value);
    }

    private static ValidationException invalidSchema(String message) {
        return new ValidationException("CAPABILITY_SCHEMA_INVALID", message);
    }
}
