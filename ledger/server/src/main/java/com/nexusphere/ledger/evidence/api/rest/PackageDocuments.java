package com.nexusphere.ledger.evidence.api.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusphere.ledger.chain.AgentModel;
import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.EvidenceLink;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.evidence.application.PackageService;
import com.nexusphere.ledger.evidence.application.PromptService;
import com.nexusphere.ledger.policy.application.PolicyService;
import com.nexusphere.ledger.evidence.domain.model.EvidencePackage;
import com.nexusphere.ledger.evidence.domain.model.PackageRequest;
import com.nexusphere.ledger.server.signing.KeyController;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.transparency.application.RekorService;
import com.nexusphere.ledger.transparency.application.TimestampService;
import com.nexusphere.ledger.transparency.application.WitnessCollector;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Component
public class PackageDocuments {

    public record ExportRequest(String agentId, String principalId, Long fromSequence, Long toSequence,
                                Boolean includePrompts) {

        public static ExportRequest empty() {
            return new ExportRequest(null, null, null, null, null);
        }

        public PackageRequest toRequest() {
            return new PackageRequest(agentId, principalId, fromSequence, toSequence);
        }

        public boolean prompts() {
            return Boolean.TRUE.equals(includePrompts);
        }
    }

    public record PromptResponse(String hash, String text) {
    }

    public record PolicyResponse(String hash, String text) {
    }

    public record Scope(String agentId, String principalId, long fromSequence, long toSequence) {
    }

    private static final JsonMapper REKOR_JSON = JsonMapper.builder().build();

    public record CheckpointResponse(String format, long sequence, String headHash, Instant createdAt, String keyId,
                              List<Checkpoint.Profile> profiles, String signature, String timestamp,
                              JsonNode rekor) {

        public static CheckpointResponse of(SignedCheckpoint signed) {
            if (signed == null) {
                return null;
            }
            Checkpoint c = signed.checkpoint();
            return new CheckpointResponse(c.format(), c.sequence(), c.headHash(), c.createdAt(), c.keyId(),
                    c.profiles(), signed.signature(), signed.timestamp(),
                    signed.rekor() == null ? null : REKOR_JSON.readTree(signed.rekor()));
        }
    }

    public record LinkResponse(long sequence, String previousHash, String contentHash, String hash,
                        EvidenceController.EvidenceResponse entry) {

        public static LinkResponse of(EvidencePackage.Item item) {
            EvidenceLink l = item.link();
            return new LinkResponse(l.sequence(), l.previousHash(), l.contentHash(), l.hash(),
                    item.entry() == null ? null : EvidenceController.EvidenceResponse.of(item.entry()));
        }
    }

    public record ProofResponse(long sequence, List<String> hashes, String statement, String receipt) {
    }

    public record LogResponse(String checkpoint, List<ProofResponse> proofs) {

        public static LogResponse of(EvidencePackage.Log log) {
            Base64.Encoder base64 = Base64.getEncoder();
            return new LogResponse(log.checkpoint(), log.proofs().entrySet().stream()
                    .map(e -> new ProofResponse(e.getKey(), e.getValue().stream().map(base64::encodeToString).toList(),
                            base64.encodeToString(log.statements().get(e.getKey())),
                            base64.encodeToString(log.receipts().get(e.getKey()))))
                    .toList());
        }
    }

    public record PackageResponse(String format, Instant createdAt, Scope scope, List<KeyController.KeyResponse> keys,
                           CheckpointResponse anchor, CheckpointResponse checkpoint, long disclosed,
                           List<LinkResponse> links, LogResponse log,
                           @JsonInclude(JsonInclude.Include.NON_NULL) List<PromptResponse> prompts,
                           @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PolicyResponse> policies) {
    }

    private final PackageService packages;
    private final LedgerSigner signer;
    private final WitnessCollector witnesses;
    private final TimestampService timestamps;
    private final RekorService rekor;
    private final PromptService promptTexts;
    private final PolicyService policyTexts;
    private final JsonMapper json;

    PackageDocuments(PackageService packages, LedgerSigner signer, WitnessCollector witnesses,
                     TimestampService timestamps, RekorService rekor, PromptService promptTexts,
                     PolicyService policyTexts, JsonMapper json) {
        this.promptTexts = promptTexts;
        this.policyTexts = policyTexts;
        this.packages = packages;
        this.signer = signer;
        this.witnesses = witnesses;
        this.timestamps = timestamps;
        this.rekor = rekor;
        this.json = json;
    }

    public PackageResponse export(PackageRequest request) {
        return export(request, false);
    }

    public PackageResponse export(PackageRequest request, boolean includePrompts) {
        EvidencePackage p = packages.export(request);
        return new PackageResponse(EvidencePackage.FORMAT, p.createdAt(),
                new Scope(p.agentId(), p.principalId(), p.fromSequence(), p.toSequence()),
                keys(), CheckpointResponse.of(p.anchor()), CheckpointResponse.of(p.checkpoint()), p.disclosed(),
                p.items().stream().map(LinkResponse::of).toList(), LogResponse.of(p.log()),
                includePrompts ? prompts(p) : null, policies(p));
    }

    private List<PolicyResponse> policies(EvidencePackage p) {
        Set<String> hashes = new TreeSet<>();
        for (EvidencePackage.Item item : p.items()) {
            if (item.entry() != null && item.entry().attributes() != null) {
                String hash = item.entry().attributes().get(PolicyService.HASH_ATTRIBUTE);
                if (hash != null) {
                    hashes.add(hash);
                }
            }
        }
        return policyTexts.texts(hashes).entrySet().stream()
                .map(e -> new PolicyResponse(e.getKey(), e.getValue())).toList();
    }

    private List<PromptResponse> prompts(EvidencePackage p) {
        Set<String> hashes = new TreeSet<>();
        for (EvidencePackage.Item item : p.items()) {
            AgentModel model = item.entry() == null ? null : item.entry().model();
            if (model != null) {
                if (model.promptHash() != null) {
                    hashes.add(model.promptHash());
                }
                if (model.configHash() != null) {
                    hashes.add(model.configHash());
                }
            }
        }
        return promptTexts.texts(hashes).entrySet().stream()
                .map(e -> new PromptResponse(e.getKey(), e.getValue())).toList();
    }

    public JsonNode tree(Object value) {
        return json.valueToTree(value);
    }

    public List<KeyController.KeyResponse> keys() {
        return signer.keys().stream().map(KeyController.KeyResponse::of).toList();
    }

    public PackageReport verify(JsonNode document, String publicKey) {
        return PackageVerifier.verify(document, publicKey, json.valueToTree(keys()), witnesses.witnessKeys(), 0);
    }

    public PackageReport verifyWithAnchors(JsonNode document) {
        return PackageVerifier.verify(document, null, json.valueToTree(keys()), witnesses.witnessKeys(), 0,
                new PackageVerifier.TimestampPolicy(timestamps.certificates(), false),
                new PackageVerifier.RekorPolicy(rekor.keys(), false));
    }
}
