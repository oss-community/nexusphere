package com.nexusphere.ledger.evidence.domain.repository;

import com.nexusphere.ledger.chain.SignedCheckpoint;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CheckpointRepository {

    void append(SignedCheckpoint checkpoint);

    void appendTimestamp(long sequence, byte[] token, Instant genTime, Instant recordedAt);

    List<SignedCheckpoint> withoutTimestamp(int limit);

    void appendRekor(long sequence, long logIndex, String entry, Instant recordedAt);

    List<SignedCheckpoint> withoutRekor(int limit);

    Optional<SignedCheckpoint> latest();

    Optional<SignedCheckpoint> findBySequence(long sequence);

    List<SignedCheckpoint> list(long afterSequence, int limit);

    List<SignedCheckpoint> between(long afterSequence, long upToSequence);

    Optional<SignedCheckpoint> firstAtOrAfter(long sequence);

    Optional<SignedCheckpoint> lastBefore(long sequence);
}
