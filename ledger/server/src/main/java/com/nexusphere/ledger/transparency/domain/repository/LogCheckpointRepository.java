package com.nexusphere.ledger.transparency.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LogCheckpointRepository {

    record StoredCheckpoint(long size, byte[] root, String note, Instant createdAt) {
    }

    record Cosignature(long size, String witness, String line, Instant createdAt) {
    }

    void append(StoredCheckpoint checkpoint);

    Optional<StoredCheckpoint> latest();

    Optional<StoredCheckpoint> find(long size);

    List<StoredCheckpoint> all();

    void addCosignature(Cosignature cosignature);

    List<Cosignature> cosignatures(long size);

    Optional<Long> lastCosignedSize(String witness);
}
