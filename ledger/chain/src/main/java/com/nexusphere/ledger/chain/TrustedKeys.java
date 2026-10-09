package com.nexusphere.ledger.chain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TrustedKeys {

    private final Map<String, SigningKeys.PublicKeyInfo> trusted;
    private final Map<String, KeyRevocation> revoked;

    private TrustedKeys(Map<String, SigningKeys.PublicKeyInfo> trusted, Map<String, KeyRevocation> revoked) {
        this.trusted = trusted;
        this.revoked = revoked;
    }

    public static TrustedKeys from(SigningKeys.PublicKeyInfo pinned, Collection<SigningKeys.PublicKeyInfo> keys,
                                   Collection<KeyRotation> rotations) {
        return from(pinned, keys, rotations, List.of());
    }

    public static TrustedKeys from(SigningKeys.PublicKeyInfo pinned, Collection<SigningKeys.PublicKeyInfo> keys,
                                   Collection<KeyRotation> rotations, Collection<KeyRevocation> revocations) {
        Map<String, SigningKeys.PublicKeyInfo> known = new LinkedHashMap<>();
        for (SigningKeys.PublicKeyInfo key : keys) {
            known.put(key.keyId(), key);
        }
        Map<String, KeyRevocation> revoked = revoked(walk(pinned, known, rotations, Map.of()), revocations);
        return new TrustedKeys(walk(pinned, known, rotations, revoked), revoked);
    }

    public static TrustedKeys listed(Collection<SigningKeys.PublicKeyInfo> keys,
                                     Collection<KeyRevocation> revocations) {
        Map<String, SigningKeys.PublicKeyInfo> listed = new LinkedHashMap<>();
        for (SigningKeys.PublicKeyInfo key : keys) {
            listed.put(key.keyId(), key);
        }
        return new TrustedKeys(listed, revoked(listed, revocations));
    }

    private static Map<String, SigningKeys.PublicKeyInfo> walk(SigningKeys.PublicKeyInfo pinned,
                                                               Map<String, SigningKeys.PublicKeyInfo> known,
                                                               Collection<KeyRotation> rotations,
                                                               Map<String, KeyRevocation> revoked) {
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
                KeyRevocation revocation = revoked.get(rotation.previousKeyId());
                if (endorser != null && !trusted.containsKey(rotation.keyId())
                        && (revocation == null || !revocation.covers(rotation.activatedAt()))
                        && rotation.verifiedByPrevious(endorser)) {
                    trusted.put(rotation.keyId(), rotation.key());
                    changed = true;
                }
            }
        }
        return trusted;
    }

    private static Map<String, KeyRevocation> revoked(Map<String, SigningKeys.PublicKeyInfo> trusted,
                                                      Collection<KeyRevocation> revocations) {
        Map<String, KeyRevocation> revoked = new LinkedHashMap<>();
        for (KeyRevocation revocation : revocations) {
            SigningKeys.PublicKeyInfo revoker = trusted.get(revocation.revokerKeyId());
            if (!trusted.containsKey(revocation.keyId()) || revoker == null || !revocation.verify(revoker)) {
                continue;
            }
            KeyRevocation known = revoked.get(revocation.keyId());
            if (known == null || revocation.compromisedAt().isBefore(known.compromisedAt())) {
                revoked.put(revocation.keyId(), revocation);
            }
        }
        return revoked;
    }

    public Optional<SigningKeys.PublicKeyInfo> find(String keyId) {
        return Optional.ofNullable(trusted.get(keyId));
    }

    public Collection<SigningKeys.PublicKeyInfo> all() {
        return trusted.values();
    }

    public Optional<KeyRevocation> revocation(String keyId) {
        return Optional.ofNullable(revoked.get(keyId));
    }

    public Collection<KeyRevocation> revocations() {
        return revoked.values();
    }
}
