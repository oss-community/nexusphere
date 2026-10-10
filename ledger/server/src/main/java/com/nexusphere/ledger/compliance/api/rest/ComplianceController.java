package com.nexusphere.ledger.compliance.api.rest;

import com.nexusphere.ledger.compliance.application.Compliance;
import com.nexusphere.ledger.compliance.domain.model.ComplianceProfile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/public/v1/compliance")
class ComplianceController {

    record ComplianceResponse(String region, List<Map<String, Object>> profiles, Map<String, Object> effective) {
    }

    private final Compliance compliance;

    ComplianceController(Compliance compliance) {
        this.compliance = compliance;
    }

    @GetMapping
    ComplianceResponse get() {
        return new ComplianceResponse(compliance.region(),
                compliance.profiles().stream().map(ComplianceController::profile).toList(), compliance.effective());
    }

    private static Map<String, Object> profile(ComplianceProfile profile) {
        Map<String, Object> map = new LinkedHashMap<>(profile.definition());
        map.put("digest", profile.digest());
        return map;
    }
}
