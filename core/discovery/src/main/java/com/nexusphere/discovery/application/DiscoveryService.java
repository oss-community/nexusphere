package com.nexusphere.discovery.application;

import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.authorization.contract.AuthorizationDecision;
import com.nexusphere.authorization.contract.AuthorizationRequest;
import com.nexusphere.authorization.contract.Authorizer;
import com.nexusphere.capability.contract.CapabilitySnapshot;
import com.nexusphere.discovery.contract.CapabilityDiscovery;
import com.nexusphere.discovery.contract.CapabilityQuery;
import com.nexusphere.discovery.contract.DiscoveredCapability;
import com.nexusphere.discovery.domain.model.DiscoveryCriteria;
import com.nexusphere.discovery.domain.model.DiscoveryReach;
import com.nexusphere.discovery.domain.model.DiscoveryScope;
import com.nexusphere.discovery.domain.repository.CapabilityDiscoveryPort;
import com.nexusphere.federation.contract.FederationDirectory;
import com.nexusphere.federation.contract.FederationSnapshot;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.network.contract.NetworkDirectory;
import com.nexusphere.network.contract.NetworkSnapshot;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.reference.ResourceReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DiscoveryService implements CapabilityDiscovery {

    public record NetworkListing(NetworkId networkId, String name, UUID federationId, List<String> federationScopes) {
    }

    private final CapabilityDiscoveryPort capabilities;
    private final FederationDirectory federations;
    private final NetworkDirectory networks;
    private final Authorizer authorizer;

    DiscoveryService(CapabilityDiscoveryPort capabilities, FederationDirectory federations, NetworkDirectory networks,
                     Authorizer authorizer) {
        this.capabilities = capabilities;
        this.federations = federations;
        this.networks = networks;
        this.authorizer = authorizer;
    }

    @Override
    public List<DiscoveredCapability> discover(PrincipalContext principal, CapabilityQuery query,
                                               ExecutionContext context) {
        DiscoveryCriteria criteria = new DiscoveryCriteria(query.typeCode(), query.ownerType(), query.organizationId());
        return reach(principal, DiscoveryScope.parse(query.scope()), query.originNetworkId(), context).stream()
                .flatMap(reach -> capabilities.findCapabilities(reach.networkId()).stream()
                        .filter(capability -> criteria.admits(capability, reach.federated()))
                        .map(capability -> discovered(capability, reach)))
                .toList();
    }

    @Override
    public Optional<DiscoveredCapability> find(PrincipalContext principal, CapabilityId capabilityId,
                                               ExecutionContext context) {
        DiscoveryCriteria criteria = new DiscoveryCriteria(null, null, null);
        for (DiscoveryReach reach : reach(principal, DiscoveryScope.ALL, null, context)) {
            Optional<DiscoveredCapability> found = capabilities.findCapabilities(reach.networkId()).stream()
                    .filter(capability -> capability.id().equals(capabilityId))
                    .filter(capability -> criteria.admits(capability, reach.federated()))
                    .findFirst().map(capability -> discovered(capability, reach));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    public List<NetworkListing> networks(PrincipalContext principal) {
        Map<NetworkId, FederationSnapshot> active = federations.findActiveFor(principal.networkId()).stream()
                .collect(Collectors.toMap(federation -> partner(federation, principal.networkId()),
                        federation -> federation, (first, second) -> first));
        return networks.findAllActive().stream().filter(network -> !network.id().equals(principal.networkId()))
                .map(network -> {
                    FederationSnapshot federation = active.get(network.id());
                    return new NetworkListing(network.id(), network.name(),
                            federation == null ? null : federation.id(),
                            federation == null ? List.of() : federation.scopes().stream().sorted().toList());
                })
                .toList();
    }

    private List<DiscoveryReach> reach(PrincipalContext principal, DiscoveryScope scope, NetworkId origin,
                                       ExecutionContext context) {
        NetworkId home = principal.networkId();
        List<DiscoveryReach> reach = new ArrayList<>();
        if (scope.includesLocal() && (origin == null || origin.equals(home))) {
            AuthorizationDecision decision = authorizer.require(discover(principal, home), context);
            reach.add(new DiscoveryReach(home, name(home), false, null, null, decision.id()));
        }
        if (!scope.includesFederated()) {
            return reach;
        }
        for (FederationSnapshot federation : federations.findActiveFor(home)) {
            NetworkId partner = partner(federation, home);
            if (origin != null && !origin.equals(partner)) {
                continue;
            }
            AuthorizationDecision decision = authorizer.authorize(discover(principal, partner), context);
            if (decision.allowed()) {
                reach.add(new DiscoveryReach(partner, name(partner), true, decision.federationId(),
                        decision.trustRelationshipId(), decision.id()));
            }
        }
        return reach;
    }

    private static AuthorizationRequest discover(PrincipalContext principal, NetworkId target) {
        return AuthorizationRequest.of(principal, Actions.CAPABILITY_DISCOVER,
                new ResourceReference("network", target.toString(), target));
    }

    private String name(NetworkId networkId) {
        return networks.find(networkId).map(NetworkSnapshot::name).orElse(null);
    }

    private static NetworkId partner(FederationSnapshot federation, NetworkId home) {
        return federation.proposerNetworkId().equals(home) ? federation.partnerNetworkId()
                : federation.proposerNetworkId();
    }

    private static DiscoveredCapability discovered(CapabilitySnapshot capability, DiscoveryReach reach) {
        return new DiscoveredCapability(capability.id(), reach.networkId(), reach.networkName(), capability.name(),
                capability.description(), capability.typeCode(), capability.typeVersion(), capability.ownerType(),
                capability.ownerId(), capability.accountableOrganizationId(), capability.visibility(),
                reach.federated(), reach.federationId(), reach.trustRelationshipId(), reach.decisionId());
    }
}
