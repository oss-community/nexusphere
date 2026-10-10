package com.nexusphere.ledger.transparency.api.rest;

import com.nexusphere.ledger.transparency.application.TimestampService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TimestampController {

    private final TimestampService timestamps;

    TimestampController(TimestampService timestamps) {
        this.timestamps = timestamps;
    }

    @GetMapping("/public/v1/timestamp-authority")
    TimestampService.Authority authority() {
        return timestamps.authority();
    }
}
