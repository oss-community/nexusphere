package com.nexusphere.identity.application;

import com.nexusphere.identity.contract.CredentialIssued;
import com.nexusphere.identity.contract.CredentialRevoked;
import com.nexusphere.identity.contract.CredentialVerifier;
import com.nexusphere.identity.domain.model.Credential;
import com.nexusphere.identity.domain.model.Identity;
import com.nexusphere.identity.domain.repository.CredentialRepository;
import com.nexusphere.identity.domain.repository.IdentityRepository;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class CredentialService implements CredentialVerifier {

    public record IssuedCredential(Credential credential, String secret) {
    }

    public static final String ROTATED = "ROTATED";
    public static final String REVOKED = "REVOKED";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IdentityRepository identities;
    private final CredentialRepository credentials;
    private final CredentialProperties properties;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    CredentialService(IdentityRepository identities, CredentialRepository credentials, CredentialProperties properties,
                      DomainEventPublisher events, TimeProvider time) {
        this.identities = identities;
        this.credentials = credentials;
        this.properties = properties;
        this.events = events;
        this.time = time;
    }

    public IssuedCredential issue(IdentityId identityId, Instant expiresAt, ExecutionContext context) {
        Identity identity = identities.findById(identityId)
                .orElseThrow(() -> new NotFoundException("Identity", identityId));
        if (!identity.isActive()) {
            throw new ConflictException("IDENTITY_NOT_ACTIVE", "Identity " + identityId + " is not active");
        }
        Instant now = time.now();
        Instant expiry = expiresAt == null ? now.plus(properties.ttl()) : expiresAt;
        if (expiry.isAfter(now.plus(properties.maxTtl()))) {
            throw new ValidationException("CREDENTIAL_EXPIRY_TOO_LONG",
                    "A credential cannot be valid for longer than " + properties.maxTtl());
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Credential credential = credentials.save(Credential.issue(UUID.randomUUID(), identityId, hash(secret), now,
                expiry));
        events.publish(new CredentialIssued(now, identityId, credential.id(), credential.expiresAt()), context);
        return new IssuedCredential(credential, secret);
    }

    public IssuedCredential rotate(IdentityId identityId, Instant expiresAt, ExecutionContext context) {
        IssuedCredential issued = issue(identityId, expiresAt, context);
        Instant now = time.now();
        credentials.findByIdentity(identityId).stream()
                .filter(credential -> !credential.id().equals(issued.credential().id()))
                .filter(credential -> credential.revokedAt() == null)
                .forEach(credential -> revoke(credential, now, ROTATED, context));
        return issued;
    }

    public Credential revoke(IdentityId identityId, UUID credentialId, ExecutionContext context) {
        Credential credential = credentials.findById(identityId, credentialId)
                .orElseThrow(() -> new NotFoundException("Credential", credentialId));
        return revoke(credential, time.now(), REVOKED, context);
    }

    @Transactional(readOnly = true)
    public List<Credential> list(IdentityId identityId) {
        identities.findById(identityId).orElseThrow(() -> new NotFoundException("Identity", identityId));
        return credentials.findByIdentity(identityId);
    }

    public Instant now() {
        return time.now();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> verify(IdentityId identityId, String secret) {
        if (secret == null || secret.isEmpty()) {
            return Optional.empty();
        }
        boolean active = identities.findById(identityId).map(Identity::isActive).orElse(false);
        Instant now = time.now();
        byte[] presented = hash(secret).getBytes(StandardCharsets.US_ASCII);
        UUID matched = null;
        for (Credential credential : credentials.findByIdentity(identityId)) {
            boolean matches = MessageDigest.isEqual(presented,
                    credential.secretHash().getBytes(StandardCharsets.US_ASCII));
            if (matches && credential.usableAt(now)) {
                matched = credential.id();
            }
        }
        return active ? Optional.ofNullable(matched) : Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean usable(IdentityId identityId, UUID credentialId) {
        return credentials.findById(identityId, credentialId).map(credential -> credential.usableAt(time.now()))
                .orElse(false);
    }

    private Credential revoke(Credential credential, Instant now, String reason, ExecutionContext context) {
        Credential revoked = credentials.save(credential.revoke(now));
        events.publish(new CredentialRevoked(now, credential.identityId(), credential.id(), reason), context);
        return revoked;
    }

    private static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
