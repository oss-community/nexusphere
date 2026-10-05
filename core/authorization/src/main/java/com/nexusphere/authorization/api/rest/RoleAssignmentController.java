package com.nexusphere.authorization.api.rest;

import com.nexusphere.authorization.application.RoleAssignmentService;
import com.nexusphere.authorization.domain.model.RoleAssignment;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.id.Identifier;
import com.nexusphere.shared.id.PrincipalId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/role-assignments")
class RoleAssignmentController {

    record AssignRoleRequest(@NotBlank String principalId, @NotBlank String role) {
    }

    record RoleAssignmentResponse(String id, String networkId, String principalId, String role, String status,
                                  String assignedBy, Instant assignedAt, Instant revokedAt) {

        static RoleAssignmentResponse of(RoleAssignment assignment) {
            return new RoleAssignmentResponse(assignment.id().toString(), assignment.networkId().toString(),
                    assignment.principalId().toString(), assignment.role().name(), assignment.status().name(),
                    assignment.assignedBy().map(PrincipalId::toString).orElse(null), assignment.assignedAt(),
                    assignment.revokedAt().orElse(null));
        }
    }

    private final RoleAssignmentService assignments;

    RoleAssignmentController(RoleAssignmentService assignments) {
        this.assignments = assignments;
    }

    @PostMapping
    ResponseEntity<RoleAssignmentResponse> assign(@PathVariable String networkId,
                                                  @Valid @RequestBody AssignRoleRequest request,
                                                  PrincipalContext principal, ExecutionContext context) {
        RoleAssignment assignment = assignments.assign(principal, PrincipalId.of(request.principalId()),
                request.role(), context);
        return ResponseEntity.created(URI.create("/api/v1/networks/" + networkId + "/role-assignments/"
                + assignment.id())).body(RoleAssignmentResponse.of(assignment));
    }

    @GetMapping
    List<RoleAssignmentResponse> list(PrincipalContext principal, @RequestParam(required = false) String principalId) {
        return assignments.list(principal, principalId == null ? null : PrincipalId.of(principalId)).stream()
                .map(RoleAssignmentResponse::of).toList();
    }

    @PostMapping("/{assignmentId}/revoke")
    RoleAssignmentResponse revoke(PrincipalContext principal, @PathVariable String assignmentId,
                                  ExecutionContext context) {
        return RoleAssignmentResponse.of(assignments.revoke(principal,
                Identifier.parse(assignmentId, "RoleAssignmentId"), context));
    }
}
