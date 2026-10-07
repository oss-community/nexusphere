package com.nexusphere.ledger.evidence.domain.repository;

import com.nexusphere.ledger.chain.SignedCheckpoint;

import java.util.List;
import java.util.Optional;

public interface CheckpointRepository {

    void append(SignedCheckpoint checkpoint);

    Optional<SignedCheckpoint> latest();

    Optional<SignedCheckpoint> findBySequence(long sequence);

    List<SignedCheckpoint> list(long afterSequence, int limit);

    List<SignedCheckpoint> between(long afterSequence, long upToSequence);
}
