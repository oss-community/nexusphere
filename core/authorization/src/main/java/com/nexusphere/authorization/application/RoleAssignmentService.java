package com.nexusphere.authorization.application;

import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.authorization.contract.AuthorizationRequest;
import com.nexusphere.authorization.contract.RoleAssigned;
import com.nexusphere.authorization.contract.RoleRevoked;
import com.nexusphere.authorization.domain.model.Role;
import com.nexusphere.authorization.domain.model.RoleAssignment;
import com.nexusphere.authorization.domain.repository.RoleAssignmentRepository;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.membership.contract.PrincipalResolver;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.PrincipalId;
import com.nexusphere.shared.reference.ResourceReference;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class RoleAssignmentService {

    private final RoleAssignmentRepository assignments;
    private final AuthorizationService authorization;
    private final PrincipalResolver principals;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    RoleAssignmentService(RoleAssignmentRepository assignments, AuthorizationService authorization,
                          PrincipalResolver principals, DomainEventPublisher events, TimeProvider time) {
        this.assignments = assignments;
        this.authorization = authorization;
        this.principals = principals;
        this.events = events;
        this.time = time;
    }

    public RoleAssignment assign(PrincipalContext principal, PrincipalId assignee, String roleName,
                                 ExecutionContext context) {
        Role role = Role.parse(roleName);
        authorization.require(AuthorizationRequest.of(principal, Actions.ROLE_ASSIGN,
                new ResourceReference("principal", assignee.toString(), principal.networkId())), context);
        principals.find(principal.networkId(), assignee)
                .orElseThrow(() -> new NotFoundException("Principal", assignee));
        if (assignee.equals(principal.principalId())) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "ROLE_SELF_ASSIGNMENT",
                    "A principal cannot assign a role to itself");
        }
        if (!authorization.actionsHeldBy(principal).containsAll(role.actions())) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "ROLE_EXCEEDS_GRANTOR",
                    "A principal cannot assign a role with actions it does not hold");
        }
        if (assignments.findActive(principal.networkId(), assignee).stream().anyMatch(a -> a.role() == role)) {
            throw new ConflictException("ROLE_ALREADY_ASSIGNED", "Principal " + assignee + " already holds " + role);
        }
        RoleAssignment saved = assignments.save(RoleAssignment.assign(UUID.randomUUID(), principal.networkId(),
                assignee, role, principal.principalId(), time.now()));
        events.publish(new RoleAssigned(time.now(), saved.networkId(), saved.id(), saved.principalId(),
                saved.role().name(), principal.principalId()), context.withNetwork(saved.networkId()));
        return saved;
    }

    public RoleAssignment revoke(PrincipalContext principal, UUID id, ExecutionContext context) {
        RoleAssignment assignment = assignments.findById(principal.networkId(), id)
                .orElseThrow(() -> new NotFoundException("RoleAssignment", id));
        authorization.require(AuthorizationRequest.of(principal, Actions.ROLE_ASSIGN,
                new ResourceReference("principal", assignment.principalId().toString(), principal.networkId())),
                context);
        assignment.revoke(time.now());
        RoleAssignment saved = assignments.save(assignment);
        events.publish(new RoleRevoked(time.now(), saved.networkId(), saved.id(), saved.principalId(),
                saved.role().name(), principal.principalId()), context.withNetwork(saved.networkId()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<RoleAssignment> list(PrincipalContext principal, PrincipalId filter) {
        return filter == null ? assignments.findAll(principal.networkId())
                : assignments.findActive(principal.networkId(), filter);
    }
}
