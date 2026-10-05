package com.nexusphere.organization.api.rest;

import com.nexusphere.organization.application.OrganizationService;
import com.nexusphere.organization.domain.model.Organization;
import com.nexusphere.shared.context.Caller;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/organizations")
class OrganizationController {

    record OrganizationRequest(@NotBlank @Size(max = Organization.NAME_MAX_LENGTH) String name) {
    }

    record OrganizationResponse(String id, String networkId, String name, String status,
                                Instant createdAt, Instant updatedAt) {

        static OrganizationResponse of(Organization organization) {
            return new OrganizationResponse(organization.id().toString(), organization.networkId().toString(),
                    organization.name(), organization.status().name(), organization.createdAt(),
                    organization.updatedAt());
        }
    }

    private final OrganizationService organizations;

    OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @PostMapping
    ResponseEntity<OrganizationResponse> register(@PathVariable String networkId,
                                                  @Valid @RequestBody OrganizationRequest request,
                                                  Caller caller, ExecutionContext context) {
        caller.requireAdministratorOf(NetworkId.of(networkId));
        Organization organization = organizations.register(NetworkId.of(networkId), request.name(), context);
        return ResponseEntity.created(URI.create("/api/v1/networks/" + networkId + "/organizations/" + organization.id()))
                .body(OrganizationResponse.of(organization));
    }

    @GetMapping
    List<OrganizationResponse> list(@PathVariable String networkId, Caller caller) {
        caller.requireMemberOf(NetworkId.of(networkId));
        return organizations.list(NetworkId.of(networkId)).stream().map(OrganizationResponse::of).toList();
    }

    @GetMapping("/{organizationId}")
    OrganizationResponse get(@PathVariable String networkId, @PathVariable String organizationId, Caller caller) {
        caller.requireMemberOf(NetworkId.of(networkId));
        return OrganizationResponse.of(organizations.get(NetworkId.of(networkId), OrganizationId.of(organizationId)));
    }

    @PutMapping("/{organizationId}")
    OrganizationResponse rename(@PathVariable String networkId, @PathVariable String organizationId,
                                @Valid @RequestBody OrganizationRequest request, Caller caller,
                                ExecutionContext context) {
        caller.requireAdministratorOf(NetworkId.of(networkId));
        return OrganizationResponse.of(organizations.rename(NetworkId.of(networkId), OrganizationId.of(organizationId),
                request.name(), context));
    }

    @PostMapping("/{organizationId}/deactivate")
    OrganizationResponse deactivate(@PathVariable String networkId, @PathVariable String organizationId,
                                    Caller caller, ExecutionContext context) {
        caller.requireAdministratorOf(NetworkId.of(networkId));
        return OrganizationResponse.of(organizations.deactivate(NetworkId.of(networkId),
                OrganizationId.of(organizationId), context));
    }
}
