package com.nexusphere.ledger.transparency.domain.repository;

import java.util.List;
import java.util.Optional;

public interface MerkleNodeRepository {

    void put(int level, long index, byte[] hash);

    Optional<byte[]> find(int level, long index);

    List<byte[]> range(int level, long from, int count);
}
