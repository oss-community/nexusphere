package com.nexusphere.ledger.chain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class TrustedKeys {

    private final Map<String, SigningKeys.PublicKeyInfo> trusted;

    private TrustedKeys(Map<String, SigningKeys.PublicKeyInfo> trusted) {
        this.trusted = trusted;
    }

    public static TrustedKeys from(SigningKeys.PublicKeyInfo pinned, Collection<SigningKeys.PublicKeyInfo> keys,
                                   Collection<KeyRotation> rotations) {
        Map<String, SigningKeys.PublicKeyInfo> known = new LinkedHashMap<>();
        for (SigningKeys.PublicKeyInfo key : keys) {
            known.put(key.keyId(), key);
        }
        Map<String, SigningKeys.PublicKeyInfo> trusted = new LinkedHashMap<>();
        trusted.put(pinned.keyId(), pinned);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (KeyRotation rotation : rotations) {
                if (!rotation.verifiedByKey()) {
                    continue;
                }
                SigningKeys.PublicKeyInfo previous = known.get(rotation.previousKeyId());
                if (trusted.containsKey(rotation.keyId()) && previous != null
                        && !trusted.containsKey(previous.keyId())) {
                    trusted.put(previous.keyId(), previous);
                    changed = true;
                }
                SigningKeys.PublicKeyInfo endorser = trusted.get(rotation.previousKeyId());
                if (endorser != null && !trusted.containsKey(rotation.keyId())
                        && rotation.verifiedByPrevious(endorser)) {
                    trusted.put(rotation.keyId(), rotation.key());
                    changed = true;
                }
            }
        }
        return new TrustedKeys(trusted);
    }

    public Optional<SigningKeys.PublicKeyInfo> find(String keyId) {
        return Optional.ofNullable(trusted.get(keyId));
    }

    public Collection<SigningKeys.PublicKeyInfo> all() {
        return trusted.values();
    }
}
