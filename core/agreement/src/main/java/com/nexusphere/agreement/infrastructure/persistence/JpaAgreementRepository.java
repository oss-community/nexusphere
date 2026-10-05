package com.nexusphere.agreement.infrastructure.persistence;

import com.nexusphere.agreement.domain.model.Agreement;
import com.nexusphere.agreement.domain.model.AgreementParty;
import com.nexusphere.agreement.domain.model.AgreementVersion;
import com.nexusphere.agreement.domain.repository.AgreementRepository;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaAgreementRepository implements AgreementRepository {

    private final AgreementJpaRepository agreements;
    private final AgreementVersionJpaRepository versions;
    private final JsonTerms terms;

    JpaAgreementRepository(AgreementJpaRepository agreements, AgreementVersionJpaRepository versions,
                           JsonTerms terms) {
        this.agreements = agreements;
        this.versions = versions;
        this.terms = terms;
    }

    @Override
    public Agreement save(Agreement agreement) {
        try {
            AgreementEntity saved = agreements.saveAndFlush(toEntity(agreement));
            versions.saveAllAndFlush(agreement.versions().stream().map(version -> toEntity(agreement.id(), version))
                    .toList());
            return toDomain(saved);
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("AGREEMENT_VERSION_CONFLICT",
                    "Agreement " + agreement.id() + " was changed concurrently");
        }
    }

    @Override
    public Optional<Agreement> findById(UUID id) {
        return agreements.findById(id).map(this::toDomain);
    }

    @Override
    public List<Agreement> findInvolving(NetworkId networkId) {
        return agreements.findInvolving(networkId.value()).stream().map(this::toDomain).toList();
    }

    private static AgreementEntity toEntity(Agreement agreement) {
        return new AgreementEntity(agreement.id(), agreement.type(), agreement.title(),
                agreement.capabilityId().value(), agreement.capabilityNetworkId().value(),
                agreement.capabilityTypeCode(), agreement.proposer().organizationId().value(),
                agreement.proposer().networkId().value(), agreement.counterparty().organizationId().value(),
                agreement.counterparty().networkId().value(), agreement.status(), agreement.current().number(),
                agreement.createdAt(), agreement.activatedAt().orElse(null), agreement.closedAt().orElse(null),
                agreement.closingReason().orElse(null), agreement.lockVersion());
    }

    private AgreementVersionEntity toEntity(UUID agreementId, AgreementVersion version) {
        return new AgreementVersionEntity(version.id(), agreementId, version.number(), terms.write(version.terms()),
                String.join("\n", version.changes()), version.onBehalfOf().organizationId().value(),
                version.onBehalfOf().networkId().value(), version.proposedBy().value(),
                version.proposedByIdentity().value(), version.delegationId().orElse(null),
                version.decisionId().orElse(null), version.federationId().orElse(null), version.proposedAt(),
                version.acceptedBy().map(PrincipalId::value).orElse(null), version.acceptedAt().orElse(null),
                version.acceptanceDecisionId().orElse(null), version.superseded());
    }

    private Agreement toDomain(AgreementEntity entity) {
        List<AgreementVersion> history = versions.findByAgreementIdOrderByNumber(entity.getId()).stream()
                .map(this::toDomain).toList();
        return Agreement.restore(entity.getId(), entity.getType(), entity.getTitle(),
                new CapabilityId(entity.getCapabilityId()), new NetworkId(entity.getCapabilityNetworkId()),
                entity.getCapabilityTypeCode(),
                new AgreementParty(new OrganizationId(entity.getProposerOrganizationId()),
                        new NetworkId(entity.getProposerNetworkId())),
                new AgreementParty(new OrganizationId(entity.getCounterpartyOrganizationId()),
                        new NetworkId(entity.getCounterpartyNetworkId())),
                entity.getStatus(), history, entity.getCreatedAt(), entity.getActivatedAt(), entity.getClosedAt(),
                entity.getClosingReason(), entity.getVersion());
    }

    private AgreementVersion toDomain(AgreementVersionEntity entity) {
        List<String> changes = Arrays.stream(entity.getChanges().split("\n")).filter(change -> !change.isBlank())
                .toList();
        return new AgreementVersion(entity.getId(), entity.getNumber(), terms.read(entity.getTerms()), changes,
                new AgreementParty(new OrganizationId(entity.getOnBehalfOrganizationId()),
                        new NetworkId(entity.getOnBehalfNetworkId())),
                new PrincipalId(entity.getProposedBy()), new IdentityId(entity.getProposedByIdentity()),
                entity.getDelegationId(), entity.getDecisionId(), entity.getFederationId(), entity.getProposedAt(),
                entity.getAcceptedBy() == null ? null : new PrincipalId(entity.getAcceptedBy()),
                entity.getAcceptedAt(), entity.getAcceptanceDecisionId(), entity.isSuperseded());
    }
}
