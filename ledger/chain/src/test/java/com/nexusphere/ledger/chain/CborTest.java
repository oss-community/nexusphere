package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CborTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void valuesEncodeAsInRfc8949() {
        assertThat(HEX.formatHex(Cbor.encode(0L))).isEqualTo("00");
        assertThat(HEX.formatHex(Cbor.encode(100L))).isEqualTo("1864");
        assertThat(HEX.formatHex(Cbor.encode(1_000_000L))).isEqualTo("1a000f4240");
        assertThat(HEX.formatHex(Cbor.encode(1_000_000_000_000L))).isEqualTo("1b000000e8d4a51000");
        assertThat(HEX.formatHex(Cbor.encode(-1000L))).isEqualTo("3903e7");
        assertThat(HEX.formatHex(Cbor.encode("IETF"))).isEqualTo("6449455446");
        assertThat(HEX.formatHex(Cbor.encode(new byte[]{1, 2, 3, 4}))).isEqualTo("4401020304");
        assertThat(HEX.formatHex(Cbor.encode(List.of(1L, List.of(2L, 3L))))).isEqualTo("8201820203");
        assertThat(HEX.formatHex(Cbor.encode(new Cbor.Tagged(18, List.of())))).isEqualTo("d280");
    }

    @Test
    void mapKeysAreSortedDeterministically() {
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put("a", 1L);
        map.put(-1L, 2L);
        map.put(10L, 3L);

        assertThat(HEX.formatHex(Cbor.encode(map))).isEqualTo("a30a0320026161" + "01");
    }

    @Test
    void decodingReturnsWhatWasEncoded() {
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put(1L, -8L);
        map.put("x", List.of(new byte[]{9}, "y", true));

        Map<?, ?> decoded = (Map<?, ?>) Cbor.decode(Cbor.encode(map));

        assertThat(decoded.get(1L)).isEqualTo(-8L);
        List<?> list = (List<?>) decoded.get("x");
        assertThat((byte[]) list.get(0)).containsExactly(9);
        assertThat(list.get(1)).isEqualTo("y");
        assertThat(list.get(2)).isEqualTo(true);
    }

    @Test
    void malformedInputIsRejected() {
        assertThatThrownBy(() -> Cbor.decode(HEX.parseHex("44010203"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cbor.decode(HEX.parseHex("0000"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cbor.decode(HEX.parseHex("9f00ff"))).isInstanceOf(IllegalArgumentException.class);
    }
}
