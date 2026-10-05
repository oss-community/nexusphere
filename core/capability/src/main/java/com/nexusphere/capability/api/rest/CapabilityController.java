package com.nexusphere.capability.api.rest;

import com.nexusphere.capability.application.CapabilityService;
import com.nexusphere.capability.domain.model.Capability;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.id.CapabilityId;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/capabilities")
class CapabilityController {

    record RegisterCapabilityRequest(@NotBlank @Size(max = Capability.NAME_MAX_LENGTH) String name,
                                     @Size(max = Capability.DESCRIPTION_MAX_LENGTH) String description,
                                     @NotBlank String typeCode, Integer typeVersion, String ownerType,
                                     String ownerId, Map<String, Object> specification, String visibility) {
    }

    record PublishRequest(String visibility) {
    }

    record VisibilityRequest(@NotBlank String visibility) {
    }

    record CapabilityResponse(String id, String networkId, String name, String description, String ownerType,
                              String ownerId, String accountableOrganizationId, String typeId, String typeCode,
                              int typeVersion, Map<String, Object> specification, String visibility, String status,
                              Instant createdAt, Instant publishedAt, Instant withdrawnAt) {

        static CapabilityResponse of(Capability capability) {
            return new CapabilityResponse(capability.id().toString(), capability.networkId().toString(),
                    capability.name(), capability.description(), capability.owner().type().name(),
                    capability.owner().id().toString(),
                    capability.accountableOrganizationId().map(OrganizationId::toString).orElse(null),
                    capability.typeId().toString(), capability.typeCode(), capability.typeVersion(),
                    capability.specification(), capability.visibility().name(), capability.status().name(),
                    capability.createdAt(), capability.publishedAt().orElse(null),
                    capability.withdrawnAt().orElse(null));
        }
    }

    private final CapabilityService capabilities;

    CapabilityController(CapabilityService capabilities) {
        this.capabilities = capabilities;
    }

    @PostMapping
    ResponseEntity<CapabilityResponse> register(@PathVariable String networkId,
                                                @Valid @RequestBody RegisterCapabilityRequest request,
                                                PrincipalContext principal, ExecutionContext context) {
        Capability capability = capabilities.register(principal, new CapabilityService.Registration(request.name(),
                request.description(), request.typeCode(), request.typeVersion(), request.ownerType(),
                request.ownerId(), request.specification(), request.visibility()), context);
        return ResponseEntity.created(URI.create("/api/v1/networks/" + networkId + "/capabilities/" + capability.id()))
                .body(CapabilityResponse.of(capability));
    }

    @GetMapping
    List<CapabilityResponse> list(PrincipalContext principal, @RequestParam(required = false) String typeCode,
                                  @RequestParam(required = false) String ownerType,
                                  @RequestParam(required = false) String ownerId) {
        return capabilities.list(principal, new CapabilityService.Filter(typeCode, ownerType, ownerId)).stream()
                .map(CapabilityResponse::of).toList();
    }

    @GetMapping("/{capabilityId}")
    CapabilityResponse get(PrincipalContext principal, @PathVariable String capabilityId) {
        return CapabilityResponse.of(capabilities.get(principal, CapabilityId.of(capabilityId)));
    }

    @PostMapping("/{capabilityId}/publish")
    CapabilityResponse publish(PrincipalContext principal, @PathVariable String capabilityId,
                               @RequestBody(required = false) PublishRequest request, ExecutionContext context) {
        return CapabilityResponse.of(capabilities.publish(principal, CapabilityId.of(capabilityId),
                request == null ? null : request.visibility(), context));
    }

    @PutMapping("/{capabilityId}/visibility")
    CapabilityResponse changeVisibility(PrincipalContext principal, @PathVariable String capabilityId,
                                        @Valid @RequestBody VisibilityRequest request, ExecutionContext context) {
        return CapabilityResponse.of(capabilities.changeVisibility(principal, CapabilityId.of(capabilityId),
                request.visibility(), context));
    }

    @PostMapping("/{capabilityId}/withdraw")
    CapabilityResponse withdraw(PrincipalContext principal, @PathVariable String capabilityId,
                                ExecutionContext context) {
        return CapabilityResponse.of(capabilities.withdraw(principal, CapabilityId.of(capabilityId), context));
    }
}
