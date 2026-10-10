package com.nexusphere.ledger.evidence.api.rest;

import com.nexusphere.ledger.evidence.domain.model.PackageRequest;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.verifier.PackageReport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class PackageController {

    private final PackageDocuments documents;

    PackageController(PackageDocuments documents) {
        this.documents = documents;
    }

    @PostMapping("/api/v1/packages")
    ResponseEntity<PackageDocuments.PackageResponse> export(Caller caller,
                                                            @RequestBody(required = false)
                                                            PackageDocuments.ExportRequest request) {
        caller.requireOperator();
        PackageDocuments.ExportRequest r = request == null ? new PackageDocuments.ExportRequest(null, null, null, null)
                : request;
        PackageDocuments.PackageResponse body = documents.export(new PackageRequest(r.agentId(), r.principalId(),
                r.fromSequence(), r.toSequence()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"evidence-package-"
                        + body.checkpoint().sequence() + ".json\"")
                .body(body);
    }

    @PostMapping("/api/v1/packages/verify")
    PackageReport verify(Caller caller, @RequestParam(required = false) String publicKey,
                         @RequestBody JsonNode body) {
        caller.requireOperator();
        return documents.verify(body, publicKey == null || publicKey.isBlank() ? null : publicKey);
    }
}
