package com.nexusphere.membership.api.rest;

import com.nexusphere.membership.contract.PrincipalContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/principal")
class PrincipalController {

    record PrincipalResponse(String principalId, String identityId, String identityType, String networkId,
                             String organizationId, boolean networkAdministrator) {
    }

    @GetMapping
    PrincipalResponse current(PrincipalContext principal) {
        return new PrincipalResponse(principal.principalId().toString(), principal.identityId().toString(),
                principal.identityType(), principal.networkId().toString(),
                principal.organizationId() == null ? null : principal.organizationId().toString(),
                principal.networkAdministrator());
    }
}
