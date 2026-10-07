package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalJsonTest {

    @Test
    void sortsKeysAtEveryLevelAndHasNoWhitespace() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("z", "last");
        inner.put("a", "first");
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("b", inner);
        outer.put("a", 7L);
        outer.put("c", null);

        assertThat(CanonicalJson.write(outer)).isEqualTo("{\"a\":7,\"b\":{\"a\":\"first\",\"z\":\"last\"},\"c\":null}");
    }

    @Test
    void escapesQuotesBackslashesAndControlCharacters() {
        String text = "a\"b\\c\nd\te\u0001f";

        assertThat(CanonicalJson.write(Map.of("k", text))).isEqualTo("{\"k\":\"a\\\"b\\\\c\\nd\\te\\u0001f\"}");
    }

    @Test
    void keepsNonAsciiCharactersAsTheyAre() {
        assertThat(CanonicalJson.write(Map.of("name", "سامان"))).isEqualTo("{\"name\":\"سامان\"}");
    }

    @Test
    void rejectsUnsupportedValues() {
        assertThatThrownBy(() -> CanonicalJson.write(Map.of("x", 1.5d)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
