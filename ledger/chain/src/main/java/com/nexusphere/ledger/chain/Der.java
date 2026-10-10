package com.nexusphere.ledger.chain;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Der {

    public static final int BOOLEAN = 0x01;
    public static final int INTEGER = 0x02;
    public static final int OCTET_STRING = 0x04;
    public static final int NULL = 0x05;
    public static final int OID = 0x06;
    public static final int SEQUENCE = 0x30;
    public static final int SET = 0x31;
    public static final int GENERALIZED_TIME = 0x18;

    private Der() {
    }

    public record Node(int tag, byte[] data, int start, int contentStart, int end) {

        public byte[] encoded() {
            return Arrays.copyOfRange(data, start, end);
        }

        public byte[] content() {
            return Arrays.copyOfRange(data, contentStart, end);
        }

        public List<Node> children() {
            List<Node> children = new ArrayList<>();
            int offset = contentStart;
            while (offset < end) {
                Node child = read(data, offset, end);
                children.add(child);
                offset = child.end();
            }
            return children;
        }

        public Node child(int index) {
            List<Node> children = children();
            if (index >= children.size()) {
                throw new IllegalArgumentException("DER element " + index + " is missing");
            }
            return children.get(index);
        }

        public Node expect(int expected) {
            if (tag != expected) {
                throw new IllegalArgumentException(String.format("expected DER tag %02x but found %02x", expected,
                        tag));
            }
            return this;
        }

        public String oid() {
            expect(OID);
            byte[] bytes = content();
            StringBuilder out = new StringBuilder();
            long value = 0;
            for (int i = 0; i < bytes.length; i++) {
                value = (value << 7) | (bytes[i] & 0x7f);
                if ((bytes[i] & 0x80) == 0) {
                    if (out.isEmpty()) {
                        long first = Math.min(value / 40, 2);
                        out.append(first).append('.').append(value - first * 40);
                    } else {
                        out.append('.').append(value);
                    }
                    value = 0;
                }
            }
            return out.toString();
        }

        public BigInteger integer() {
            expect(INTEGER);
            return new BigInteger(content());
        }

        public Instant generalizedTime() {
            expect(GENERALIZED_TIME);
            String text = new String(content(), StandardCharsets.US_ASCII);
            if (!text.endsWith("Z") || text.length() < 15) {
                throw new IllegalArgumentException("GeneralizedTime must be UTC");
            }
            String seconds = text.substring(0, 14);
            String fraction = text.substring(14, text.length() - 1);
            Instant instant = LocalDateTime.parse(seconds, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    .toInstant(ZoneOffset.UTC);
            if (fraction.startsWith(".") && fraction.length() > 1) {
                String digits = (fraction.substring(1) + "000000000").substring(0, 9);
                instant = instant.plusNanos(Long.parseLong(digits));
            }
            return instant;
        }
    }

    public static Node read(byte[] data) {
        Node node = read(data, 0, data.length);
        if (node.end() != data.length) {
            throw new IllegalArgumentException("trailing bytes after DER element");
        }
        return node;
    }

    static Node read(byte[] data, int offset, int limit) {
        if (offset + 2 > limit) {
            throw new IllegalArgumentException("truncated DER element");
        }
        int tag = data[offset] & 0xff;
        if ((tag & 0x1f) == 0x1f) {
            throw new IllegalArgumentException("high DER tag numbers are not supported");
        }
        int first = data[offset + 1] & 0xff;
        int position = offset + 2;
        long length;
        if (first < 0x80) {
            length = first;
        } else {
            int count = first & 0x7f;
            if (count == 0 || count > 4 || position + count > limit) {
                throw new IllegalArgumentException("invalid DER length");
            }
            length = 0;
            for (int i = 0; i < count; i++) {
                length = (length << 8) | (data[position++] & 0xff);
            }
        }
        if (position + length > limit) {
            throw new IllegalArgumentException("truncated DER element");
        }
        return new Node(tag, data, offset, position, (int) (position + length));
    }

    public static byte[] encode(int tag, byte[]... parts) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            content.writeBytes(part);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.size();
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length);
        } else {
            out.write(0x83);
            out.write(length >> 16);
            out.write(length >> 8);
            out.write(length);
        }
        out.writeBytes(content.toByteArray());
        return out.toByteArray();
    }

    public static byte[] integer(BigInteger value) {
        return encode(INTEGER, value.toByteArray());
    }

    public static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBase128(out, Long.parseLong(parts[0]) * 40 + Long.parseLong(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            writeBase128(out, Long.parseLong(parts[i]));
        }
        return encode(OID, out.toByteArray());
    }

    private static void writeBase128(ByteArrayOutputStream out, long value) {
        int groups = 1;
        for (long v = value >>> 7; v > 0; v >>>= 7) {
            groups++;
        }
        for (int i = groups - 1; i >= 0; i--) {
            int b = (int) ((value >>> (7 * i)) & 0x7f);
            out.write(i == 0 ? b : b | 0x80);
        }
    }
}
