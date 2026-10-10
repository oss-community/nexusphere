package com.nexusphere.ledger.transparency.api.rest;

import com.nexusphere.ledger.transparency.application.RekorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RekorController {

    private final RekorService rekor;

    RekorController(RekorService rekor) {
        this.rekor = rekor;
    }

    @GetMapping("/public/v1/rekor")
    RekorService.Log log() {
        return rekor.log();
    }
}
