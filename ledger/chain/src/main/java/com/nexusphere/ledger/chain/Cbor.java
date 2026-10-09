package com.nexusphere.ledger.chain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Cbor {

    public record Tagged(long tag, Object value) {
    }

    private Cbor() {
    }

    public static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }

    public static Object decode(byte[] data) {
        Reader reader = new Reader(data);
        Object value = reader.read();
        if (reader.position != data.length) {
            throw new IllegalArgumentException("Trailing bytes after CBOR item");
        }
        return value;
    }

    private static void write(ByteArrayOutputStream out, Object value) {
        switch (value) {
            case null -> out.write(0xf6);
            case Boolean b -> out.write(b ? 0xf5 : 0xf4);
            case Integer i -> writeInteger(out, i);
            case Long l -> writeInteger(out, l);
            case byte[] bytes -> {
                head(out, 2, bytes.length);
                out.writeBytes(bytes);
            }
            case String text -> {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                head(out, 3, bytes.length);
                out.writeBytes(bytes);
            }
            case List<?> list -> {
                head(out, 4, list.size());
                list.forEach(item -> write(out, item));
            }
            case Map<?, ?> map -> {
                List<byte[][]> pairs = new ArrayList<>();
                map.forEach((key, item) -> pairs.add(new byte[][]{encode(key), encode(item)}));
                pairs.sort((a, b) -> Arrays.compareUnsigned(a[0], b[0]));
                head(out, 5, pairs.size());
                pairs.forEach(pair -> {
                    out.writeBytes(pair[0]);
                    out.writeBytes(pair[1]);
                });
            }
            case Tagged tagged -> {
                head(out, 6, tagged.tag());
                write(out, tagged.value());
            }
            default -> throw new IllegalArgumentException("Cannot encode " + value.getClass().getSimpleName());
        }
    }

    private static void writeInteger(ByteArrayOutputStream out, long value) {
        if (value >= 0) {
            head(out, 0, value);
        } else {
            head(out, 1, -1 - value);
        }
    }

    private static void head(ByteArrayOutputStream out, int major, long argument) {
        int type = major << 5;
        if (argument < 24) {
            out.write(type | (int) argument);
        } else if (argument < 0x100) {
            out.write(type | 24);
            out.write((int) argument);
        } else if (argument < 0x10000) {
            out.write(type | 25);
            writeBig(out, argument, 2);
        } else if (argument < 0x100000000L) {
            out.write(type | 26);
            writeBig(out, argument, 4);
        } else {
            out.write(type | 27);
            writeBig(out, argument, 8);
        }
    }

    private static void writeBig(ByteArrayOutputStream out, long value, int length) {
        for (int i = length - 1; i >= 0; i--) {
            out.write((int) (value >>> (8 * i)) & 0xff);
        }
    }

    private static final class Reader {

        private static final int MAX_DEPTH = 32;

        private final byte[] data;
        private int position;
        private int depth;

        Reader(byte[] data) {
            this.data = data;
        }

        Object read() {
            if (++depth > MAX_DEPTH) {
                throw new IllegalArgumentException("CBOR nested too deeply");
            }
            int initial = next();
            int major = initial >>> 5;
            int info = initial & 0x1f;
            Object value = switch (major) {
                case 0 -> argument(info);
                case 1 -> -1 - argument(info);
                case 2 -> bytes(length(info));
                case 3 -> new String(bytes(length(info)), StandardCharsets.UTF_8);
                case 4 -> {
                    int size = length(info);
                    List<Object> list = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        list.add(read());
                    }
                    yield list;
                }
                case 5 -> {
                    int size = length(info);
                    Map<Object, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < size; i++) {
                        Object key = read();
                        if (map.put(key, read()) != null || key instanceof byte[]) {
                            throw new IllegalArgumentException("Duplicate or unsupported CBOR map key");
                        }
                    }
                    yield map;
                }
                case 6 -> new Tagged(argument(info), read());
                default -> switch (info) {
                    case 20 -> false;
                    case 21 -> true;
                    case 22 -> null;
                    default -> throw new IllegalArgumentException("Unsupported CBOR simple value " + info);
                };
            };
            depth--;
            return value;
        }

        private int next() {
            if (position >= data.length) {
                throw new IllegalArgumentException("Truncated CBOR");
            }
            return data[position++] & 0xff;
        }

        private long argument(int info) {
            if (info < 24) {
                return info;
            }
            int length = switch (info) {
                case 24 -> 1;
                case 25 -> 2;
                case 26 -> 4;
                case 27 -> 8;
                default -> throw new IllegalArgumentException("Indefinite or reserved CBOR length");
            };
            long value = 0;
            for (int i = 0; i < length; i++) {
                value = (value << 8) | next();
            }
            if (value < 0) {
                throw new IllegalArgumentException("CBOR integer out of range");
            }
            return value;
        }

        private int length(int info) {
            long length = argument(info);
            if (length > data.length - position) {
                throw new IllegalArgumentException("Truncated CBOR");
            }
            return (int) length;
        }

        private byte[] bytes(int length) {
            byte[] bytes = Arrays.copyOfRange(data, position, position + length);
            position += length;
            return bytes;
        }
    }
}
