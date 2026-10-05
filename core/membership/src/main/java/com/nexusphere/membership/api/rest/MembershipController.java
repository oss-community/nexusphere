package com.nexusphere.membership.api.rest;

import com.nexusphere.membership.application.MembershipService;
import com.nexusphere.membership.domain.model.Membership;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/memberships")
class MembershipController {

    record ActivateMembershipRequest(@NotBlank String identityId, String organizationId) {
    }

    record MembershipResponse(String id, String principalId, String identityId, String networkId, String organizationId,
                              String status, Instant joinedAt, Instant terminatedAt) {

        static MembershipResponse of(Membership membership) {
            return new MembershipResponse(membership.id().toString(), membership.principalId().toString(),
                    membership.identityId().toString(), membership.networkId().toString(),
                    membership.organizationId().map(OrganizationId::toString).orElse(null),
                    membership.status().name(), membership.joinedAt(), membership.terminatedAt().orElse(null));
        }
    }

    private final MembershipService memberships;

    MembershipController(MembershipService memberships) {
        this.memberships = memberships;
    }

    @PostMapping
    ResponseEntity<MembershipResponse> activate(@PathVariable String networkId,
                                                @Valid @RequestBody ActivateMembershipRequest request,
                                                ExecutionContext context) {
        OrganizationId organization = request.organizationId() == null ? null : OrganizationId.of(request.organizationId());
        Membership membership = memberships.activate(NetworkId.of(networkId), IdentityId.of(request.identityId()),
                organization, context);
        return ResponseEntity.created(URI.create("/api/v1/networks/" + networkId + "/memberships/" + membership.id()))
                .body(MembershipResponse.of(membership));
    }

    @GetMapping
    List<MembershipResponse> list(@PathVariable String networkId) {
        return memberships.list(NetworkId.of(networkId)).stream().map(MembershipResponse::of).toList();
    }

    @GetMapping("/{membershipId}")
    MembershipResponse get(@PathVariable String networkId, @PathVariable String membershipId) {
        return MembershipResponse.of(memberships.get(NetworkId.of(networkId), membershipId(membershipId)));
    }

    @PostMapping("/{membershipId}/terminate")
    MembershipResponse terminate(@PathVariable String networkId, @PathVariable String membershipId,
                                 ExecutionContext context) {
        return MembershipResponse.of(memberships.terminate(NetworkId.of(networkId), membershipId(membershipId), context));
    }

    private static UUID membershipId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("INVALID_IDENTIFIER", "Invalid MembershipId: " + value);
        }
    }
}
