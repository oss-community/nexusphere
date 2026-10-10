package com.nexusphere.ledger.transparency.api.rest;

import com.nexusphere.ledger.evidence.api.rest.PackageDocuments;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.transparency.application.IndexService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
class IndexController {

    record ProofRequest(Map<String, Object> term, Long sequence) {
    }

    private final IndexService index;
    private final PackageDocuments documents;

    IndexController(IndexService index, PackageDocuments documents) {
        this.index = index;
        this.documents = documents;
    }

    @PostMapping("/api/v1/index/proofs")
    Map<String, Object> proof(Caller caller, @RequestBody ProofRequest request) {
        caller.requireOperator();
        Map<String, Object> proof = index.proof(request.term(), request.sequence());
        proof.put("keys", documents.keys());
        return proof;
    }
}
