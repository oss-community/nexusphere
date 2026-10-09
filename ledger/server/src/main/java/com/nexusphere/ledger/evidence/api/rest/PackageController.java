package com.nexusphere.ledger.evidence.api.rest;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.EvidenceLink;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.evidence.application.PackageService;
import com.nexusphere.ledger.evidence.domain.model.EvidencePackage;
import com.nexusphere.ledger.evidence.domain.model.PackageRequest;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.signing.KeyController;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

@RestController
class PackageController {

    record ExportRequest(String agentId, String principalId, Long fromSequence, Long toSequence) {
    }

    record Scope(String agentId, String principalId, long fromSequence, long toSequence) {
    }

    record CheckpointResponse(String format, long sequence, String headHash, Instant createdAt, String keyId,
                              String signature) {

        static CheckpointResponse of(SignedCheckpoint signed) {
            if (signed == null) {
                return null;
            }
            Checkpoint c = signed.checkpoint();
            return new CheckpointResponse(Checkpoint.FORMAT, c.sequence(), c.headHash(), c.createdAt(), c.keyId(),
                    signed.signature());
        }
    }

    record LinkResponse(long sequence, String previousHash, String contentHash, String hash,
                        EvidenceController.EvidenceResponse entry) {

        static LinkResponse of(EvidencePackage.Item item) {
            EvidenceLink l = item.link();
            return new LinkResponse(l.sequence(), l.previousHash(), l.contentHash(), l.hash(),
                    item.entry() == null ? null : EvidenceController.EvidenceResponse.of(item.entry()));
        }
    }

    record ProofResponse(long sequence, List<String> hashes, String statement, String receipt) {
    }

    record LogResponse(String checkpoint, List<ProofResponse> proofs) {

        static LogResponse of(EvidencePackage.Log log) {
            Base64.Encoder base64 = Base64.getEncoder();
            return new LogResponse(log.checkpoint(), log.proofs().entrySet().stream()
                    .map(e -> new ProofResponse(e.getKey(), e.getValue().stream().map(base64::encodeToString).toList(),
                            base64.encodeToString(log.statements().get(e.getKey())),
                            base64.encodeToString(log.receipts().get(e.getKey()))))
                    .toList());
        }
    }

    record PackageResponse(String format, Instant createdAt, Scope scope, List<KeyController.KeyResponse> keys,
                           CheckpointResponse anchor, CheckpointResponse checkpoint, long disclosed,
                           List<LinkResponse> links, LogResponse log) {
    }

    private final PackageService packages;
    private final LedgerSigner signer;

    PackageController(PackageService packages, LedgerSigner signer) {
        this.packages = packages;
        this.signer = signer;
    }

    @PostMapping("/api/v1/packages")
    ResponseEntity<PackageResponse> export(Caller caller, @RequestBody(required = false) ExportRequest request) {
        caller.requireOperator();
        ExportRequest r = request == null ? new ExportRequest(null, null, null, null) : request;
        EvidencePackage p = packages.export(new PackageRequest(r.agentId(), r.principalId(), r.fromSequence(),
                r.toSequence()));
        PackageResponse body = new PackageResponse(EvidencePackage.FORMAT, p.createdAt(),
                new Scope(p.agentId(), p.principalId(), p.fromSequence(), p.toSequence()),
                signer.keys().stream().map(KeyController.KeyResponse::of).toList(),
                CheckpointResponse.of(p.anchor()), CheckpointResponse.of(p.checkpoint()), p.disclosed(),
                p.items().stream().map(LinkResponse::of).toList(), LogResponse.of(p.log()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"evidence-package-"
                        + p.checkpoint().checkpoint().sequence() + ".json\"")
                .body(body);
    }

    @PostMapping("/api/v1/packages/verify")
    PackageReport verify(Caller caller, @RequestParam(required = false) String publicKey,
                         @RequestBody JsonNode body) {
        caller.requireOperator();
        return PackageVerifier.verify(body, publicKey == null || publicKey.isBlank() ? null : publicKey);
    }
}
