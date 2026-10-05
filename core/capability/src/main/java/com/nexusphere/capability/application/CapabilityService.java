package com.nexusphere.capability.application;

import com.nexusphere.capability.contract.CapabilityDirectory;
import com.nexusphere.capability.contract.CapabilitySnapshot;
import com.nexusphere.capability.domain.model.Capability;
import com.nexusphere.capability.domain.model.CapabilityOwner;
import com.nexusphere.capability.domain.model.CapabilityType;
import com.nexusphere.capability.domain.model.CapabilityVisibility;
import com.nexusphere.capability.domain.model.OwnerType;
import com.nexusphere.capability.domain.repository.CapabilityRepository;
import com.nexusphere.identity.contract.IdentityDirectory;
import com.nexusphere.identity.contract.IdentitySnapshot;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.membership.contract.PrincipalResolver;
import com.nexusphere.network.contract.NetworkDirectory;
import com.nexusphere.organization.contract.OrganizationDirectory;
import com.nexusphere.organization.contract.OrganizationSnapshot;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class CapabilityService implements CapabilityDirectory {

    public record Registration(String name, String description, String typeCode, Integer typeVersion,
                               String ownerType, String ownerId, Map<String, Object> specification,
                               String visibility) {
    }

    public record Filter(String typeCode, String ownerType, String ownerId) {
    }

    private record ResolvedOwner(CapabilityOwner owner, OrganizationId accountableOrganizationId) {
    }

    private final CapabilityRepository capabilities;
    private final CapabilityTypeService types;
    private final IdentityDirectory identities;
    private final OrganizationDirectory organizations;
    private final NetworkDirectory networks;
    private final PrincipalResolver principals;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    CapabilityService(CapabilityRepository capabilities, CapabilityTypeService types, IdentityDirectory identities,
                      OrganizationDirectory organizations, NetworkDirectory networks, PrincipalResolver principals,
                      DomainEventPublisher events, TimeProvider time) {
        this.capabilities = capabilities;
        this.types = types;
        this.identities = identities;
        this.organizations = organizations;
        this.networks = networks;
        this.principals = principals;
        this.events = events;
        this.time = time;
    }

    public Capability register(PrincipalContext principal, Registration registration, ExecutionContext context) {
        NetworkId networkId = principal.networkId();
        requireActiveNetwork(networkId);
        CapabilityType type = types.resolve(registration.typeCode(), registration.typeVersion());
        ResolvedOwner resolved = resolveOwner(principal, registration.ownerType(), registration.ownerId());
        if (!Capability.manages(resolved.owner(), resolved.accountableOrganizationId(), principal.identityId(),
                principal.organizationId())) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "CAPABILITY_OWNER_NOT_MANAGED",
                    "The principal cannot register capabilities for this owner");
        }
        CapabilityVisibility visibility = registration.visibility() == null ? CapabilityVisibility.PRIVATE
                : CapabilityVisibility.parse(registration.visibility());
        Capability capability = Capability.register(CapabilityId.newId(), networkId, resolved.owner(),
                resolved.accountableOrganizationId(), registration.name(), registration.description(), type,
                registration.specification(), visibility, time.now());
        if (capabilities.existsCurrentName(networkId, resolved.owner(), capability.name())) {
            throw new ConflictException("CAPABILITY_NAME_TAKEN",
                    "The owner already has a capability named " + capability.name());
        }
        return persist(capability, context);
    }

    public Capability publish(PrincipalContext principal, CapabilityId id, String visibility,
                              ExecutionContext context) {
        Capability capability = managed(principal, id);
        capability.publish(visibility == null ? null : CapabilityVisibility.parse(visibility), time.now());
        return persist(capability, context);
    }

    public Capability changeVisibility(PrincipalContext principal, CapabilityId id, String visibility,
                                       ExecutionContext context) {
        Capability capability = managed(principal, id);
        capability.changeVisibility(CapabilityVisibility.parse(visibility), time.now());
        return persist(capability, context);
    }

    public Capability withdraw(PrincipalContext principal, CapabilityId id, ExecutionContext context) {
        Capability capability = managed(principal, id);
        capability.withdraw(time.now());
        return persist(capability, context);
    }

    @Transactional(readOnly = true)
    public Capability get(PrincipalContext principal, CapabilityId id) {
        return capabilities.findById(principal.networkId(), id)
                .filter(capability -> capability.isVisibleTo(principal.identityId(), principal.organizationId()))
                .orElseThrow(() -> new NotFoundException("Capability", id));
    }

    @Transactional(readOnly = true)
    public List<Capability> list(PrincipalContext principal, Filter filter) {
        OwnerType ownerType = filter.ownerType() == null ? null : OwnerType.parse(filter.ownerType());
        UUID ownerId = filter.ownerId() == null ? null : uuid(filter.ownerId(), "ownerId");
        return capabilities.findAll(principal.networkId()).stream()
                .filter(capability -> capability.isVisibleTo(principal.identityId(), principal.organizationId()))
                .filter(capability -> filter.typeCode() == null || capability.typeCode().equals(filter.typeCode()))
                .filter(capability -> ownerType == null || capability.owner().type() == ownerType)
                .filter(capability -> ownerId == null || capability.owner().id().equals(ownerId))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CapabilitySnapshot> find(NetworkId networkId, CapabilityId id) {
        return capabilities.findById(networkId, id).map(CapabilityService::snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CapabilitySnapshot> findAvailable(NetworkId networkId) {
        return capabilities.findAll(networkId).stream().filter(Capability::isAvailable)
                .map(CapabilityService::snapshot).toList();
    }

    private Capability managed(PrincipalContext principal, CapabilityId id) {
        Capability capability = get(principal, id);
        if (!capability.isManagedBy(principal.identityId(), principal.organizationId())) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "CAPABILITY_OWNER_NOT_MANAGED",
                    "The principal does not manage capability " + id);
        }
        return capability;
    }

    private ResolvedOwner resolveOwner(PrincipalContext principal, String requestedType, String requestedId) {
        OwnerType type = requestedType == null ? defaultOwnerType(principal) : OwnerType.parse(requestedType);
        if (type == OwnerType.ORGANIZATION) {
            OrganizationId organizationId = requestedId != null ? OrganizationId.of(requestedId)
                    : Optional.ofNullable(principal.organizationId()).orElseThrow(this::ownerRequired);
            OrganizationSnapshot organization = organizations.find(principal.networkId(), organizationId)
                    .orElseThrow(() -> new NotFoundException("Organization", organizationId));
            if (!organization.active()) {
                throw new ConflictException("ORGANIZATION_NOT_ACTIVE", "Organization " + organizationId + " is not active");
            }
            return new ResolvedOwner(new CapabilityOwner(type, organizationId.value()), organizationId);
        }
        IdentityId identityId = requestedId != null ? IdentityId.of(requestedId) : principal.identityId();
        IdentitySnapshot identity = identities.find(identityId)
                .orElseThrow(() -> new NotFoundException("Identity", identityId));
        if (!identity.type().equals(type.name())) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "CAPABILITY_OWNER_TYPE_MISMATCH",
                    "Identity " + identityId + " is of type " + identity.type() + ", not " + type);
        }
        if (!identity.active()) {
            throw new ConflictException("IDENTITY_NOT_ACTIVE", "Identity " + identityId + " is not active");
        }
        PrincipalContext ownerContext;
        try {
            ownerContext = principals.resolve(identityId, principal.networkId());
        } catch (DomainException e) {
            throw new DomainException(ErrorCategory.BUSINESS_RULE_VIOLATION, "CAPABILITY_OWNER_NOT_MEMBER",
                    "Identity " + identityId + " has no active membership in network " + principal.networkId());
        }
        return new ResolvedOwner(new CapabilityOwner(type, identityId.value()), ownerContext.organizationId());
    }

    private OwnerType defaultOwnerType(PrincipalContext principal) {
        if (principal.identityType().equals("HUMAN")) {
            if (principal.organizationId() == null) {
                throw ownerRequired();
            }
            return OwnerType.ORGANIZATION;
        }
        return OwnerType.parse(principal.identityType());
    }

    private ValidationException ownerRequired() {
        return new ValidationException("CAPABILITY_OWNER_REQUIRED", "The capability owner must be specified");
    }

    private void requireActiveNetwork(NetworkId networkId) {
        boolean active = networks.find(networkId).orElseThrow(() -> new NotFoundException("Network", networkId)).active();
        if (!active) {
            throw new ConflictException("NETWORK_NOT_ACTIVE", "Network " + networkId + " is not active");
        }
    }

    private Capability persist(Capability capability, ExecutionContext context) {
        Capability saved = capabilities.save(capability);
        events.publishAll(capability.pullEvents(), context.withNetwork(capability.networkId()));
        return saved;
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("INVALID_IDENTIFIER", field + " is not a valid UUID: " + value);
        }
    }

    private static CapabilitySnapshot snapshot(Capability capability) {
        return new CapabilitySnapshot(capability.id(), capability.networkId(), capability.owner().type().name(),
                capability.owner().id(), capability.accountableOrganizationId().orElse(null), capability.name(),
                capability.typeCode(), capability.typeVersion(), capability.visibility().name(),
                capability.isAvailable());
    }
}
