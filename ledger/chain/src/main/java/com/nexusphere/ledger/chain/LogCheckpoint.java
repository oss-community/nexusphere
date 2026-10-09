package com.nexusphere.ledger.chain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

public record LogCheckpoint(String origin, long size, byte[] root) {

    public static final String SIGNATURE_PREFIX = "— ";
    private static final String COSIGNATURE_HEADER = "cosignature/v1\ntime ";

    public record Signature(String name, byte[] keyHash, byte[] signature) {

        public String line() {
            byte[] value = new byte[keyHash.length + signature.length];
            System.arraycopy(keyHash, 0, value, 0, keyHash.length);
            System.arraycopy(signature, 0, value, keyHash.length, signature.length);
            return SIGNATURE_PREFIX + name + " " + Base64.getEncoder().encodeToString(value) + "\n";
        }

        public static Signature parse(String line) {
            String body = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
            if (!body.startsWith(SIGNATURE_PREFIX)) {
                throw new IllegalArgumentException("A signature line starts with an em dash and a space");
            }
            String[] parts = body.substring(SIGNATURE_PREFIX.length()).split(" ");
            if (parts.length != 2) {
                throw new IllegalArgumentException("A signature line has a name and a signature");
            }
            byte[] value = Base64.getDecoder().decode(parts[1]);
            if (value.length < 5) {
                throw new IllegalArgumentException("A signature is too short");
            }
            return new Signature(parts[0], Arrays.copyOf(value, 4), Arrays.copyOfRange(value, 4, value.length));
        }
    }

    public record Note(LogCheckpoint checkpoint, String body, List<Signature> signatures) {

        public String text() {
            StringBuilder text = new StringBuilder(body).append('\n');
            signatures.forEach(signature -> text.append(signature.line()));
            return text.toString();
        }

        public Note with(Signature signature) {
            List<Signature> all = new ArrayList<>(signatures);
            all.add(signature);
            return new Note(checkpoint, body, List.copyOf(all));
        }

        public boolean signedBy(NoteKey key) {
            return signatures.stream().anyMatch(signature -> verify(key, signature));
        }

        public Optional<Long> cosignedBy(NoteKey key) {
            return signatures.stream().map(signature -> cosignatureTime(key, signature)).flatMap(Optional::stream)
                    .findFirst();
        }

        private boolean verify(NoteKey key, Signature signature) {
            return key.type() == NoteKey.ED25519 && key.name().equals(signature.name())
                    && Arrays.equals(key.hash(), signature.keyHash())
                    && SigningKeys.verify(key.publicKey(), body.getBytes(StandardCharsets.UTF_8),
                    Base64.getEncoder().encodeToString(signature.signature()));
        }

        private Optional<Long> cosignatureTime(NoteKey key, Signature signature) {
            if (key.type() != NoteKey.COSIGNATURE || !key.name().equals(signature.name())
                    || !Arrays.equals(key.hash(), signature.keyHash()) || signature.signature().length != 72) {
                return Optional.empty();
            }
            long time = ByteBuffer.wrap(signature.signature(), 0, 8).getLong();
            byte[] ed25519 = Arrays.copyOfRange(signature.signature(), 8, 72);
            return SigningKeys.verify(key.publicKey(), cosignedBytes(time, body),
                    Base64.getEncoder().encodeToString(ed25519)) ? Optional.of(time) : Optional.empty();
        }
    }

    public LogCheckpoint {
        if (origin == null || origin.isBlank() || origin.contains("\n")) {
            throw new IllegalArgumentException("A log origin is one non-empty line");
        }
        if (size < 0) {
            throw new IllegalArgumentException("A tree size is not negative");
        }
        if (root == null || root.length != 32) {
            throw new IllegalArgumentException("A root hash has 32 bytes");
        }
        root = root.clone();
    }

    public String body() {
        return origin + "\n" + size + "\n" + Base64.getEncoder().encodeToString(root) + "\n";
    }

    public Note sign(NoteKey key, PrivateKey privateKey) {
        if (key.type() != NoteKey.ED25519) {
            throw new IllegalArgumentException("A checkpoint is signed with an Ed25519 note key");
        }
        String body = body();
        byte[] signature = Base64.getDecoder().decode(
                SigningKeys.sign(privateKey, body.getBytes(StandardCharsets.UTF_8)));
        return new Note(this, body, List.of(new Signature(key.name(), key.hash(), signature)));
    }

    public static Signature cosign(String body, NoteKey key, PrivateKey privateKey, long time) {
        if (key.type() != NoteKey.COSIGNATURE) {
            throw new IllegalArgumentException("A cosignature uses a cosignature key");
        }
        byte[] ed25519 = Base64.getDecoder().decode(SigningKeys.sign(privateKey, cosignedBytes(time, body)));
        byte[] value = ByteBuffer.allocate(72).putLong(time).put(ed25519).array();
        return new Signature(key.name(), key.hash(), value);
    }

    public static Note parse(String text) {
        int split = text.indexOf("\n\n");
        if (split < 0) {
            throw new IllegalArgumentException("A note has a body, a blank line and signatures");
        }
        String body = text.substring(0, split + 1);
        String[] lines = body.split("\n", -1);
        if (lines.length < 4) {
            throw new IllegalArgumentException("A checkpoint has an origin, a size and a root hash");
        }
        LogCheckpoint checkpoint = new LogCheckpoint(lines[0], Long.parseLong(lines[1]),
                Base64.getDecoder().decode(lines[2]));
        if (!checkpoint.body().equals(body)) {
            throw new IllegalArgumentException("Checkpoint extension lines are not supported");
        }
        List<Signature> signatures = new ArrayList<>();
        for (String line : text.substring(split + 2).split("\n")) {
            if (!line.isEmpty()) {
                signatures.add(Signature.parse(line));
            }
        }
        if (signatures.isEmpty()) {
            throw new IllegalArgumentException("A note has at least one signature");
        }
        return new Note(checkpoint, body, List.copyOf(signatures));
    }

    private static byte[] cosignedBytes(long time, String body) {
        return (COSIGNATURE_HEADER + Long.toUnsignedString(time) + "\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] root() {
        return root.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LogCheckpoint that && origin.equals(that.origin) && size == that.size
                && Arrays.equals(root, that.root);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * origin.hashCode() + Long.hashCode(size)) + Arrays.hashCode(root);
    }

    @Override
    public String toString() {
        return body();
    }
}
