package com.nexusphere.identity.api.rest;

import com.nexusphere.identity.application.CredentialService;
import com.nexusphere.identity.application.IdentityService;
import com.nexusphere.identity.domain.model.Credential;
import com.nexusphere.identity.domain.model.Identity;
import com.nexusphere.identity.domain.model.IdentityType;
import com.nexusphere.identity.domain.model.Ownership;
import com.nexusphere.shared.context.Caller;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.Identifier;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/identities")
class IdentityController {

    record CreateIdentityRequest(
            @NotNull IdentityType type,
            @NotBlank @Size(max = Identity.DISPLAY_NAME_MAX_LENGTH) String displayName,
            String owningNetworkId,
            String owningOrganizationId,
            @Size(max = Identity.DESCRIPTOR_MAX_LENGTH) String agentProvider,
            @Size(max = Identity.DESCRIPTOR_MAX_LENGTH) String agentModel) {

        Ownership ownership() {
            if (owningNetworkId == null && owningOrganizationId == null) {
                return null;
            }
            if (owningNetworkId == null || owningOrganizationId == null) {
                throw new ValidationException("INCOMPLETE_OWNERSHIP",
                        "owningNetworkId and owningOrganizationId must be given together");
            }
            return new Ownership(NetworkId.of(owningNetworkId), OrganizationId.of(owningOrganizationId));
        }
    }

    record IdentityResponse(String id, String type, String displayName, String status, String owningNetworkId,
                            String owningOrganizationId, String agentProvider, String agentModel,
                            Instant createdAt, Instant updatedAt) {

        static IdentityResponse of(Identity identity) {
            Ownership ownership = identity.ownership().orElse(null);
            return new IdentityResponse(identity.id().toString(), identity.type().name(), identity.displayName(),
                    identity.status().name(), ownership == null ? null : ownership.networkId().toString(),
                    ownership == null ? null : ownership.organizationId().toString(), identity.agentProvider(),
                    identity.agentModel(), identity.createdAt(), identity.updatedAt());
        }
    }

    record CredentialRequest(Instant expiresAt) {

        static Instant expiresAt(CredentialRequest request) {
            return request == null ? null : request.expiresAt();
        }
    }

    record CredentialResponse(String credentialId, String identityId, String status, Instant createdAt,
                              Instant expiresAt, Instant revokedAt) {

        static CredentialResponse of(Credential credential, Instant now) {
            return new CredentialResponse(credential.id().toString(), credential.identityId().toString(),
                    credential.status(now).name(), credential.createdAt(), credential.expiresAt(),
                    credential.revokedAt());
        }
    }

    record IssuedCredentialResponse(String credentialId, String identityId, String secret, String status,
                                    Instant expiresAt) {

        static IssuedCredentialResponse of(CredentialService.IssuedCredential issued, Instant now) {
            Credential credential = issued.credential();
            return new IssuedCredentialResponse(credential.id().toString(), credential.identityId().toString(),
                    issued.secret(), credential.status(now).name(), credential.expiresAt());
        }
    }

    private final IdentityService identities;
    private final CredentialService credentials;

    IdentityController(IdentityService identities, CredentialService credentials) {
        this.identities = identities;
        this.credentials = credentials;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    IdentityResponse create(@Valid @RequestBody CreateIdentityRequest request, Caller caller,
                            ExecutionContext context) {
        Ownership ownership = request.ownership();
        if (ownership == null) {
            caller.requireOperator();
        } else {
            caller.requireAdministratorOf(ownership.networkId());
        }
        Identity identity = identities.create(new IdentityService.CreateIdentity(request.type(), request.displayName(),
                ownership, request.agentProvider(), request.agentModel()), context);
        return IdentityResponse.of(identity);
    }

    @PostMapping("/{identityId}/suspend")
    IdentityResponse suspend(@PathVariable String identityId, Caller caller, ExecutionContext context) {
        return IdentityResponse.of(identities.suspend(managed(identityId, caller), context));
    }

    @PostMapping("/{identityId}/activate")
    IdentityResponse activate(@PathVariable String identityId, Caller caller, ExecutionContext context) {
        return IdentityResponse.of(identities.activate(managed(identityId, caller), context));
    }

    @PostMapping("/{identityId}/credentials")
    ResponseEntity<IssuedCredentialResponse> issueCredential(
            @PathVariable String identityId, Caller caller, ExecutionContext context,
            @RequestBody(required = false) CredentialRequest request) {
        CredentialService.IssuedCredential issued = credentials.issue(self(identityId, caller),
                CredentialRequest.expiresAt(request), context);
        return ResponseEntity.status(HttpStatus.CREATED).body(IssuedCredentialResponse.of(issued, credentials.now()));
    }

    @PostMapping("/{identityId}/credentials/rotate")
    ResponseEntity<IssuedCredentialResponse> rotateCredential(
            @PathVariable String identityId, Caller caller, ExecutionContext context,
            @RequestBody(required = false) CredentialRequest request) {
        CredentialService.IssuedCredential issued = credentials.rotate(self(identityId, caller),
                CredentialRequest.expiresAt(request), context);
        return ResponseEntity.status(HttpStatus.CREATED).body(IssuedCredentialResponse.of(issued, credentials.now()));
    }

    @PostMapping("/{identityId}/credentials/{credentialId}/revoke")
    CredentialResponse revokeCredential(@PathVariable String identityId, @PathVariable String credentialId,
                                        Caller caller, ExecutionContext context) {
        return CredentialResponse.of(credentials.revoke(self(identityId, caller),
                Identifier.parse(credentialId, "CredentialId"), context), credentials.now());
    }

    @GetMapping("/{identityId}/credentials")
    List<CredentialResponse> listCredentials(@PathVariable String identityId, Caller caller) {
        Instant now = credentials.now();
        return credentials.list(self(identityId, caller)).stream()
                .map(credential -> CredentialResponse.of(credential, now)).toList();
    }

    private IdentityId self(String identityId, Caller caller) {
        IdentityId id = IdentityId.of(identityId);
        return caller.is(id) ? id : managed(identityId, caller);
    }

    private IdentityId managed(String identityId, Caller caller) {
        IdentityId id = IdentityId.of(identityId);
        Ownership ownership = identities.get(id).ownership().orElse(null);
        if (ownership == null) {
            caller.requireOperator();
        } else {
            caller.requireAdministratorOf(ownership.networkId());
        }
        return id;
    }
}
