package com.nexusphere.ledger.mandate;

@FunctionalInterface
public interface StatusListResolver {

    StatusList resolve(String issuer, String uri);
}
