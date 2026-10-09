import hashlib
from typing import List, Optional, Sequence


def leaf_hash(data: bytes) -> bytes:
    return hashlib.sha256(b"\x00" + data).digest()


def node_hash(left: bytes, right: bytes) -> bytes:
    return hashlib.sha256(b"\x01" + left + right).digest()


def empty_root() -> bytes:
    return hashlib.sha256(b"").digest()


def root(leaf_hashes: Sequence[bytes]) -> bytes:
    return empty_root() if not leaf_hashes else _hash(leaf_hashes, 0, len(leaf_hashes))


def inclusion_proof(leaf_hashes: Sequence[bytes], index: int, size: Optional[int] = None) -> List[bytes]:
    size = len(leaf_hashes) if size is None else size
    if index < 0 or index >= size or size > len(leaf_hashes):
        raise ValueError("index must be below the tree size")
    proof = []
    _path(leaf_hashes, index, 0, size, proof)
    return proof


def consistency_proof(leaf_hashes: Sequence[bytes], first: int, second: Optional[int] = None) -> List[bytes]:
    second = len(leaf_hashes) if second is None else second
    if first < 0 or first > second or second > len(leaf_hashes):
        raise ValueError("the first size must be between 0 and the second size")
    proof = []
    if 0 < first < second:
        _subproof(leaf_hashes, first, 0, second, True, proof)
    return proof


def root_from_inclusion(leaf: bytes, index: int, size: int, proof: Sequence[bytes]) -> Optional[bytes]:
    if index < 0 or index >= size:
        return None
    fn = index
    sn = size - 1
    r = leaf
    for p in proof:
        if sn == 0:
            return None
        if fn & 1 == 1 or fn == sn:
            r = node_hash(p, r)
            if fn & 1 == 0:
                while fn & 1 == 0 and fn != 0:
                    fn >>= 1
                    sn >>= 1
        else:
            r = node_hash(r, p)
        fn >>= 1
        sn >>= 1
    return r if sn == 0 else None


def verify_inclusion(leaf: bytes, index: int, size: int, proof: Sequence[bytes], expected_root: bytes) -> bool:
    computed = root_from_inclusion(leaf, index, size, proof)
    return computed is not None and computed == expected_root


def verify_consistency(first: int, second: int, proof: Sequence[bytes], first_root: bytes,
                       second_root: bytes) -> bool:
    if first < 0 or first > second:
        return False
    if first == second:
        return len(proof) == 0 and first_root == second_root
    if first == 0:
        return len(proof) == 0
    path = list(proof)
    if first & (first - 1) == 0:
        path.insert(0, first_root)
    if not path:
        return False
    fn = first - 1
    sn = second - 1
    while fn & 1 == 1:
        fn >>= 1
        sn >>= 1
    fr = path[0]
    sr = path[0]
    for c in path[1:]:
        if sn == 0:
            return False
        if fn & 1 == 1 or fn == sn:
            fr = node_hash(c, fr)
            sr = node_hash(c, sr)
            while fn & 1 == 0 and fn != 0:
                fn >>= 1
                sn >>= 1
        else:
            sr = node_hash(sr, c)
        fn >>= 1
        sn >>= 1
    return sn == 0 and fr == first_root and sr == second_root


def _split(size: int) -> int:
    return 1 << ((size - 1).bit_length() - 1)


def _hash(leaves, start: int, size: int) -> bytes:
    if size == 1:
        return leaves[start]
    k = _split(size)
    return node_hash(_hash(leaves, start, k), _hash(leaves, start + k, size - k))


def _path(leaves, index: int, start: int, size: int, proof: list):
    if size == 1:
        return
    k = _split(size)
    if index < k:
        _path(leaves, index, start, k, proof)
        proof.append(_hash(leaves, start + k, size - k))
    else:
        _path(leaves, index - k, start + k, size - k, proof)
        proof.append(_hash(leaves, start, k))


def _subproof(leaves, first: int, start: int, size: int, complete: bool, proof: list):
    if first == size:
        if not complete:
            proof.append(_hash(leaves, start, size))
        return
    k = _split(size)
    if first <= k:
        _subproof(leaves, first, start, k, complete, proof)
        proof.append(_hash(leaves, start + k, size - k))
    else:
        _subproof(leaves, first - k, start + k, size - k, False, proof)
        proof.append(_hash(leaves, start, k))
