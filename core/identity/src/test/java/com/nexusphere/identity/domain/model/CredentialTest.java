package com.nexusphere.identity.domain.model;

import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.IdentityId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialTest {

    private final Instant now = Instant.parse("2026-10-05T10:00:00Z");

    private Credential issued() {
        return Credential.issue(UUID.randomUUID(), IdentityId.newId(), "hash", now, now.plus(Duration.ofDays(1)));
    }

    @Test
    void aCredentialIsUsableUntilItExpiresOrIsRevoked() {
        Credential credential = issued();

        assertThat(credential.usableAt(now)).isTrue();
        assertThat(credential.status(now)).isEqualTo(CredentialStatus.ACTIVE);
        assertThat(credential.usableAt(now.plus(Duration.ofDays(1)))).isFalse();
        assertThat(credential.status(now.plus(Duration.ofDays(2)))).isEqualTo(CredentialStatus.EXPIRED);

        Credential revoked = credential.revoke(now.plusSeconds(60));

        assertThat(revoked.usableAt(now.plusSeconds(61))).isFalse();
        assertThat(revoked.status(now)).isEqualTo(CredentialStatus.REVOKED);
        assertThatThrownBy(() -> revoked.revoke(now.plusSeconds(120))).isInstanceOf(ConflictException.class);
    }

    @Test
    void aCredentialMustExpireInTheFuture() {
        assertThatThrownBy(() -> Credential.issue(UUID.randomUUID(), IdentityId.newId(), "hash", now, now))
                .isInstanceOf(ValidationException.class);
    }
}
