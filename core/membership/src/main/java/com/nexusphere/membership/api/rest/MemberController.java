package com.nexusphere.membership.api.rest;

import com.nexusphere.membership.application.MembershipService;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.OrganizationId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/identities")
class MemberController {

    record MemberResponse(String id, String type, String displayName, String status, String owningOrganizationId,
                          String membershipId, String organizationId) {

        static MemberResponse of(MembershipService.Member member) {
            return new MemberResponse(member.identity().id().toString(), member.identity().type(),
                    member.identity().displayName(), member.identity().active() ? "ACTIVE" : "SUSPENDED",
                    member.identity().owningOrganizationId() == null ? null
                            : member.identity().owningOrganizationId().toString(),
                    member.membership().id().toString(),
                    member.membership().organizationId().map(OrganizationId::toString).orElse(null));
        }
    }

    private final MembershipService memberships;

    MemberController(MembershipService memberships) {
        this.memberships = memberships;
    }

    @GetMapping
    List<MemberResponse> list(PrincipalContext principal) {
        return memberships.members(principal).stream().map(MemberResponse::of).toList();
    }

    @GetMapping("/{identityId}")
    MemberResponse get(PrincipalContext principal, @PathVariable String identityId) {
        return MemberResponse.of(memberships.member(principal, IdentityId.of(identityId)));
    }
}
