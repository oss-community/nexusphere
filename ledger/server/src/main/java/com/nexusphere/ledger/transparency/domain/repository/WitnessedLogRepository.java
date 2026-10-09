package com.nexusphere.ledger.transparency.domain.repository;

import java.time.Instant;

public interface WitnessedLogRepository {

    record WitnessedLog(String origin, long size, byte[] root, Instant updatedAt) {
    }

    WitnessedLog lock(String origin, byte[] emptyRoot, Instant now);

    void update(WitnessedLog log);
}
