package com.nexusphere.discovery.api.rest;

import com.nexusphere.discovery.application.DiscoveryService;
import com.nexusphere.discovery.contract.CapabilityQuery;
import com.nexusphere.discovery.contract.DiscoveredCapability;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/discovery")
class DiscoveryController {

    record DiscoveredCapabilityResponse(String id, String originNetworkId, String originNetworkName, String name,
                                        String description, String typeCode, int typeVersion, String ownerType,
                                        String ownerId, String accountableOrganizationId, String visibility,
                                        boolean federated, String federationId, String trustRelationshipId,
                                        String decisionId) {

        static DiscoveredCapabilityResponse of(DiscoveredCapability found) {
            return new DiscoveredCapabilityResponse(found.capabilityId().toString(),
                    found.originNetworkId().toString(), found.originNetworkName(), found.name(), found.description(),
                    found.typeCode(), found.typeVersion(), found.ownerType(), found.ownerId().toString(),
                    text(found.accountableOrganizationId()), found.visibility(), found.federated(),
                    text(found.federationId()), text(found.trustRelationshipId()), text(found.decisionId()));
        }
    }

    record NetworkResponse(String networkId, String name, boolean federated, String federationId,
                           List<String> federationScopes) {

        static NetworkResponse of(DiscoveryService.NetworkListing listing) {
            return new NetworkResponse(listing.networkId().toString(), listing.name(), listing.federationId() != null,
                    text(listing.federationId()), listing.federationScopes());
        }
    }

    private final DiscoveryService discovery;

    DiscoveryController(DiscoveryService discovery) {
        this.discovery = discovery;
    }

    @GetMapping("/capabilities")
    List<DiscoveredCapabilityResponse> capabilities(PrincipalContext principal, ExecutionContext context,
                                                    @RequestParam(required = false) String typeCode,
                                                    @RequestParam(required = false) String ownerType,
                                                    @RequestParam(required = false) String organizationId,
                                                    @RequestParam(required = false) String originNetworkId,
                                                    @RequestParam(required = false) String scope) {
        CapabilityQuery query = new CapabilityQuery(typeCode, ownerType,
                organizationId == null ? null : OrganizationId.of(organizationId),
                originNetworkId == null ? null : NetworkId.of(originNetworkId), scope);
        return discovery.discover(principal, query, context).stream().map(DiscoveredCapabilityResponse::of).toList();
    }

    @GetMapping("/capabilities/{capabilityId}")
    DiscoveredCapabilityResponse capability(PrincipalContext principal, ExecutionContext context,
                                            @PathVariable String capabilityId) {
        CapabilityId id = CapabilityId.of(capabilityId);
        return discovery.find(principal, id, context).map(DiscoveredCapabilityResponse::of)
                .orElseThrow(() -> new NotFoundException("Capability", id));
    }

    @GetMapping("/networks")
    List<NetworkResponse> networks(PrincipalContext principal) {
        return discovery.networks(principal).stream().map(NetworkResponse::of).toList();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
