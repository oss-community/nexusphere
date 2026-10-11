package com.nexusphere.ledger.approval.application;

import com.nexusphere.ledger.chain.Timestamps;
import com.nexusphere.ledger.server.security.PrincipalIdentity;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Consents {

    private Consents() {
    }

    public static Map<String, String> of(PrincipalIdentity p) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("consent.issuer", p.issuer());
        attributes.put("consent.subject", p.subject());
        attributes.put("consent.token", p.tokenHash());
        if (p.authTime() != null) {
            attributes.put("consent.authTime", Timestamps.format(p.authTime()));
        }
        return attributes;
    }
}
