package com.nexusphere.agreement.application;

import com.nexusphere.agreement.contract.AgreementCommands;
import com.nexusphere.agreement.contract.AgreementProposal;
import com.nexusphere.agreement.contract.AgreementSnapshot;
import com.nexusphere.agreement.domain.model.Agreement;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
class AgreementCommandService implements AgreementCommands {

    private final AgreementService agreements;

    AgreementCommandService(AgreementService agreements) {
        this.agreements = agreements;
    }

    @Override
    public AgreementSnapshot propose(PrincipalContext principal, AgreementProposal proposal,
                                     ExecutionContext context) {
        Agreement draft = agreements.create(principal, new AgreementService.Draft(
                proposal.capabilityId() == null ? null : proposal.capabilityId().toString(), proposal.type(),
                proposal.title(), proposal.terms(), proposal.delegationId()), context);
        Agreement proposed = agreements.propose(principal, draft.id(), draft.current().number(),
                proposal.delegationId(), context);
        return AgreementService.snapshot(proposed);
    }

    @Override
    @Transactional(readOnly = true)
    public AgreementSnapshot read(PrincipalContext principal, UUID agreementId) {
        return AgreementService.snapshot(agreements.get(principal, agreementId));
    }
}
