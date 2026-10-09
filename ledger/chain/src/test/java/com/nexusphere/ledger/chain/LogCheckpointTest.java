package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogCheckpointTest {

    private final KeyPair log = SigningKeys.generate();
    private final KeyPair witness = SigningKeys.generate();
    private final NoteKey logKey = new NoteKey("ledger.example/acme", NoteKey.ED25519, log.getPublic());
    private final NoteKey witnessKey = new NoteKey("witness.example", NoteKey.COSIGNATURE, witness.getPublic());
    private final LogCheckpoint checkpoint = new LogCheckpoint("ledger.example/acme", 42,
            MerkleTree.leafHash(new byte[]{1}));

    @Test
    void aSignedCheckpointRoundTripsAsText() {
        LogCheckpoint.Note note = checkpoint.sign(logKey, log.getPrivate());

        LogCheckpoint.Note parsed = LogCheckpoint.parse(note.text());

        assertThat(note.text()).startsWith("ledger.example/acme\n42\n").contains("\n\n— ledger.example/acme ");
        assertThat(parsed.checkpoint()).isEqualTo(checkpoint);
        assertThat(parsed.signedBy(logKey)).isTrue();
        assertThat(parsed.signedBy(new NoteKey("ledger.example/acme", NoteKey.ED25519,
                SigningKeys.generate().getPublic()))).isFalse();
    }

    @Test
    void aWitnessCosignatureCarriesItsTime() {
        LogCheckpoint.Note note = checkpoint.sign(logKey, log.getPrivate());

        LogCheckpoint.Note cosigned = LogCheckpoint.parse(note.with(
                LogCheckpoint.cosign(note.body(), witnessKey, witness.getPrivate(), 1_760_000_000L)).text());

        assertThat(cosigned.signedBy(logKey)).isTrue();
        assertThat(cosigned.cosignedBy(witnessKey)).contains(1_760_000_000L);
        assertThat(LogCheckpoint.parse(note.text()).cosignedBy(witnessKey)).isEmpty();
    }

    @Test
    void verifierKeysRoundTrip() {
        NoteKey parsed = NoteKey.parse(witnessKey.vkey());

        assertThat(parsed.name()).isEqualTo("witness.example");
        assertThat(parsed.type()).isEqualTo(NoteKey.COSIGNATURE);
        assertThat(parsed.hash()).isEqualTo(witnessKey.hash());
        assertThatThrownBy(() -> NoteKey.parse(witnessKey.vkey().replace("witness.example", "other")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aChangedBodyIsNotSigned() {
        String text = checkpoint.sign(logKey, log.getPrivate()).text().replace("\n42\n", "\n43\n");

        assertThat(LogCheckpoint.parse(text).signedBy(logKey)).isFalse();
    }
}
