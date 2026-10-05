package com.nexusphere.agreement.contract;

import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;

import java.util.UUID;

public interface AgreementCommands {

    AgreementSnapshot propose(PrincipalContext principal, AgreementProposal proposal, ExecutionContext context);

    AgreementSnapshot read(PrincipalContext principal, UUID agreementId);
}
