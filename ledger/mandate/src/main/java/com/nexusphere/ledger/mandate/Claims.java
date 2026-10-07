package com.nexusphere.ledger.mandate;

import tools.jackson.databind.JsonNode;

final class Claims {

    private Claims() {
    }

    static String text(JsonNode node, String field) {
        if (!node.path(field).isString()) {
            throw new IllegalArgumentException("The token has no " + field);
        }
        return node.path(field).asString();
    }

    static long number(JsonNode node, String field) {
        if (!node.path(field).isNumber()) {
            throw new IllegalArgumentException("The token has no " + field);
        }
        return node.path(field).asLong();
    }
}
