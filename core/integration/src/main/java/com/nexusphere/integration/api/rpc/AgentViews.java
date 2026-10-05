package com.nexusphere.integration.api.rpc;

import com.nexusphere.agreement.contract.AgreementSnapshot;
import com.nexusphere.discovery.contract.DiscoveredCapability;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.transaction.contract.TransactionSnapshot;

import java.util.List;
import java.util.Objects;

final class AgentViews {

    record PrincipalView(String principalId, String identityId, String identityType, String networkId,
                         String organizationId) {

        static PrincipalView of(PrincipalContext principal) {
            return new PrincipalView(text(principal.principalId()), text(principal.identityId()),
                    principal.identityType(), text(principal.networkId()), text(principal.organizationId()));
        }
    }

    record AgentCard(String name, String protocol, String endpoint, List<String> methods, PrincipalView principal) {
    }

    record CapabilityView(String id, String name, String description, String typeCode, int typeVersion,
                          String ownerType, String ownerId, String organizationId, String networkId,
                          String networkName, String visibility, boolean federated, String federationId) {

        static CapabilityView of(DiscoveredCapability capability) {
            return new CapabilityView(text(capability.capabilityId()), capability.name(), capability.description(),
                    capability.typeCode(), capability.typeVersion(), capability.ownerType(),
                    text(capability.ownerId()), text(capability.accountableOrganizationId()),
                    text(capability.originNetworkId()), capability.originNetworkName(), capability.visibility(),
                    capability.federated(), text(capability.federationId()));
        }
    }

    record AgreementView(String id, String type, String status, int version, String capabilityId,
                         String proposerOrganizationId, String proposerNetworkId, String counterpartyOrganizationId,
                         String counterpartyNetworkId) {

        static AgreementView of(AgreementSnapshot agreement) {
            return new AgreementView(text(agreement.id()), agreement.type(), agreement.status(),
                    agreement.currentVersion(), text(agreement.capabilityId()),
                    text(agreement.proposerOrganizationId()), text(agreement.proposerNetworkId()),
                    text(agreement.counterpartyOrganizationId()), text(agreement.counterpartyNetworkId()));
        }
    }

    record TransactionView(String id, String type, String status, String reason, String agreementId,
                           int agreementVersion, String capabilityId, String requesterNetworkId,
                           String providerNetworkId, String initiatingPrincipalId, String initiatingIdentityId,
                           String decisionId, String delegationId, String federationId) {

        static TransactionView of(TransactionSnapshot transaction) {
            return new TransactionView(text(transaction.id()), transaction.type(), transaction.status(),
                    transaction.reason(), text(transaction.agreementId()), transaction.agreementVersion(),
                    text(transaction.capabilityId()), text(transaction.requesterNetworkId()),
                    text(transaction.providerNetworkId()), text(transaction.initiatingPrincipalId()),
                    text(transaction.initiatingIdentityId()), text(transaction.decisionId()),
                    text(transaction.delegationId()), text(transaction.federationId()));
        }
    }

    private AgentViews() {
    }

    private static String text(Object value) {
        return Objects.toString(value, null);
    }
}
