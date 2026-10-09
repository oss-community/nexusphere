package com.nexusphere.ledger.server.signing;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.EvidenceStatement;
import com.nexusphere.ledger.chain.KeyRotation;
import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.LogReceipt;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.chain.Signer;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.mandate.Jws;
import com.nexusphere.ledger.mandate.MandateClaims;
import com.nexusphere.ledger.mandate.Mandates;
import com.nexusphere.ledger.mandate.StatusList;
import com.nexusphere.ledger.server.config.LedgerProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.PrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class LedgerSigner {

    private final SigningProvider provider;
    private final Signer signer;
    private final SigningKeys.PublicKeyInfo publicKey;
    private final List<SigningKey> keys;

    LedgerSigner(LedgerProperties properties, SigningProvider provider, SigningKeyStore store,
                 TransactionTemplate transactions, Clock clock) {
        this.provider = provider;
        this.signer = provider.signer();
        this.publicKey = provider.publicKey();
        LedgerProperties.Signing signing = properties.signing();
        this.keys = List.copyOf(transactions.execute(status -> activate(signing, store, clock.instant())));
    }

    private List<SigningKey> activate(LedgerProperties.Signing signing, SigningKeyStore store, Instant now) {
        store.lock();
        List<SigningKey> known = store.all();
        Optional<SigningKey> configured = known.stream().filter(k -> k.keyId().equals(publicKey.keyId())).findFirst();
        if (configured.isPresent()) {
            if (!configured.get().active()) {
                throw new IllegalStateException("The signing key " + publicKey.keyId()
                        + " was retired and cannot sign again; configure a new key");
            }
            return known;
        }
        Optional<SigningKey> active = known.stream().filter(SigningKey::active).findFirst();
        if (active.isEmpty()) {
            store.insert(new SigningKey(publicKey, now, null, null, null));
            return store.all();
        }
        SigningKey previous = active.get();
        Signer previousSigner = provider.signerFor(previous.publicKey())
                .orElseGet(() -> configuredPrevious(signing, previous));
        if (previousSigner == null && (signing == null || !signing.unendorsedRotation())) {
            throw new IllegalStateException("The signing key changes from " + previous.keyId() + " to "
                    + publicKey.keyId() + "; set ledger.signing.previous-private-key to the key of "
                    + previous.keyId() + ", or ledger.signing.unendorsed-rotation to true if that key is lost");
        }
        KeyRotation rotation = KeyRotation.issue(publicKey, signer, previous.keyId(), previousSigner, now);
        store.retire(previous.keyId(), now);
        store.insert(new SigningKey(publicKey, rotation.activatedAt(), null, rotation, null));
        return store.all();
    }

    private static Signer configuredPrevious(LedgerProperties.Signing signing, SigningKey previous) {
        if (signing == null || signing.previousPrivateKey() == null || signing.previousPrivateKey().isBlank()) {
            return null;
        }
        PrivateKey previousPrivateKey = SigningKeys.decodePrivate(signing.previousPrivateKey());
        if (!SigningKeys.matches(previousPrivateKey, previous.publicKey().publicKey())) {
            throw new IllegalStateException("ledger.signing.previous-private-key does not belong to the active "
                    + "signing key " + previous.keyId());
        }
        return Signer.of(previousPrivateKey);
    }

    public SignedCheckpoint sign(long sequence, String headHash, Instant createdAt) {
        Checkpoint checkpoint = new Checkpoint(sequence, headHash, createdAt, publicKey.keyId());
        return new SignedCheckpoint(checkpoint, SigningKeys.sign(signer, checkpoint.signedBytes()));
    }

    public LogCheckpoint.Note sign(LogCheckpoint checkpoint) {
        return checkpoint.sign(new NoteKey(checkpoint.origin(), NoteKey.ED25519, publicKey.publicKey()), signer);
    }

    public LogCheckpoint.Signature cosign(String name, String body, long time) {
        return LogCheckpoint.cosign(body, new NoteKey(name, NoteKey.COSIGNATURE, publicKey.publicKey()), signer,
                time);
    }

    public byte[] signStatement(EvidenceEntry entry, String issuer) {
        return EvidenceStatement.sign(entry, issuer, publicKey.keyId(), signer);
    }

    public byte[] signReceipt(String issuer, long treeSize, long leafIndex, List<byte[]> path, byte[] root) {
        return LogReceipt.sign(issuer, publicKey.keyId(), treeSize, leafIndex, path, root, signer);
    }

    public String signMandate(MandateClaims claims) {
        return Mandates.issue(claims, publicKey.keyId(), signer);
    }

    public String signStatusList(StatusList statusList) {
        return statusList.sign(publicKey.keyId(), signer);
    }

    public String sign(String type, Map<String, ?> payload) {
        return Jws.sign(type, publicKey.keyId(), payload, signer);
    }

    public SigningKeys.PublicKeyInfo publicKey() {
        return publicKey;
    }

    public List<SigningKey> keys() {
        return keys;
    }

    public Optional<SigningKeys.PublicKeyInfo> find(String keyId) {
        return keys.stream().filter(k -> k.keyId().equals(keyId)).map(SigningKey::publicKey).findFirst();
    }
}
