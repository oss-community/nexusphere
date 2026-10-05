package com.nexusphere.authorization.contract;

import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;

import java.util.Set;

public interface Authorizer {

    AuthorizationDecision authorize(AuthorizationRequest request, ExecutionContext context);

    AuthorizationDecision require(AuthorizationRequest request, ExecutionContext context);

    Set<String> actionsHeldBy(PrincipalContext principal);
}
