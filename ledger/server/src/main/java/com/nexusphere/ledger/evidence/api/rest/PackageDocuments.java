package com.nexusphere.ledger.evidence.api.rest;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.EvidenceLink;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.evidence.application.PackageService;
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

@Component
public class PackageDocuments {

    public record ExportRequest(String agentId, String principalId, Long fromSequence, Long toSequence) {
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
                           List<LinkResponse> links, LogResponse log) {
    }

    private final PackageService packages;
    private final LedgerSigner signer;
    private final WitnessCollector witnesses;
    private final TimestampService timestamps;
    private final RekorService rekor;
    private final JsonMapper json;

    PackageDocuments(PackageService packages, LedgerSigner signer, WitnessCollector witnesses,
                     TimestampService timestamps, RekorService rekor, JsonMapper json) {
        this.packages = packages;
        this.signer = signer;
        this.witnesses = witnesses;
        this.timestamps = timestamps;
        this.rekor = rekor;
        this.json = json;
    }

    public PackageResponse export(PackageRequest request) {
        EvidencePackage p = packages.export(request);
        return new PackageResponse(EvidencePackage.FORMAT, p.createdAt(),
                new Scope(p.agentId(), p.principalId(), p.fromSequence(), p.toSequence()),
                keys(), CheckpointResponse.of(p.anchor()), CheckpointResponse.of(p.checkpoint()), p.disclosed(),
                p.items().stream().map(LinkResponse::of).toList(), LogResponse.of(p.log()));
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
