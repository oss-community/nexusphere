package com.nexusphere.identity.application;

import com.nexusphere.identity.contract.CredentialIssued;
import com.nexusphere.identity.contract.CredentialVerifier;
import com.nexusphere.identity.domain.model.Credential;
import com.nexusphere.identity.domain.model.Identity;
import com.nexusphere.identity.domain.repository.CredentialRepository;
import com.nexusphere.identity.domain.repository.IdentityRepository;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Transactional
public class CredentialService implements CredentialVerifier {

    public record IssuedCredential(UUID credentialId, IdentityId identityId, String secret) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IdentityRepository identities;
    private final CredentialRepository credentials;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    CredentialService(IdentityRepository identities, CredentialRepository credentials, DomainEventPublisher events,
                      TimeProvider time) {
        this.identities = identities;
        this.credentials = credentials;
        this.events = events;
        this.time = time;
    }

    public IssuedCredential issue(IdentityId identityId, ExecutionContext context) {
        Identity identity = identities.findById(identityId)
                .orElseThrow(() -> new NotFoundException("Identity", identityId));
        if (!identity.isActive()) {
            throw new ConflictException("IDENTITY_NOT_ACTIVE", "Identity " + identityId + " is not active");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Credential credential = credentials.save(new Credential(UUID.randomUUID(), identityId, hash(secret), time.now()));
        events.publish(new CredentialIssued(credential.createdAt(), identityId, credential.id()), context);
        return new IssuedCredential(credential.id(), identityId, secret);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verify(IdentityId identityId, String secret) {
        if (secret == null || secret.isEmpty()) {
            return false;
        }
        boolean active = identities.findById(identityId).map(Identity::isActive).orElse(false);
        byte[] presented = hash(secret).getBytes(StandardCharsets.US_ASCII);
        boolean matches = false;
        for (Credential credential : credentials.findByIdentity(identityId)) {
            matches |= MessageDigest.isEqual(presented, credential.secretHash().getBytes(StandardCharsets.US_ASCII));
        }
        return active && matches;
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
