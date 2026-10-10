import hashlib
from dataclasses import asdict, dataclass
from typing import Dict, List, Optional

from .canonical import canonical_bytes
from .evidence import is_sha256
from .package import trusted_package_keys
from .timestamps import format_instant, parse_instant

INDEX_PROOF_FORMAT = "nexusphere-ledger/index-proof/v1"
INDEX_FORMAT = "nexusphere-ledger/index/v1"
INDEX_TERMS = {"agent": ("agentId",), "action": ("agentId", "action"), "input": ("inputHash",),
               "grant": ("delegationId",)}
DEPTH = 256
EMPTY = bytes(32)
_LEAF = b"\x00"
_NODE = b"\x01"


@dataclass(frozen=True)
class IndexCheck:
    valid: bool
    term: Dict[str, str]
    present: bool
    count: Optional[int]
    first_sequence: Optional[int]
    last_sequence: Optional[int]
    sequence: Optional[int]
    head_hash: Optional[str]
    key_id: Optional[str]
    problems: List[str]

    def to_dict(self) -> dict:
        return {_camel(key): value for key, value in asdict(self).items()}


def index_term(requested: dict) -> Dict[str, str]:
    requested = requested if isinstance(requested, dict) else {}
    kind = requested.get("type")
    fields = INDEX_TERMS.get(kind if isinstance(kind, str) else "")
    if fields is None:
        raise ValueError("type must be one of agent, action, input or grant")
    if len(requested) != len(fields) + 1:
        raise ValueError("a %s term has only type and %s" % (kind, ", ".join(fields)))
    term = {"type": kind}
    for field in fields:
        value = requested.get(field)
        if not isinstance(value, str) or not value:
            raise ValueError("%s is required for a %s term" % (field, kind))
        term[field] = value
    return term


def index_key(term: dict) -> bytes:
    return hashlib.sha256(canonical_bytes(term)).digest()


def index_value(count: int, first_sequence: int, last_sequence: int) -> dict:
    return {"count": count, "firstSequence": first_sequence, "lastSequence": last_sequence}


def value_hash(value: dict) -> bytes:
    return hashlib.sha256(canonical_bytes(value)).digest()


def leaf_hash(key: bytes, hashed_value: bytes) -> bytes:
    return hashlib.sha256(_LEAF + key + hashed_value).digest()


def root_from(key: bytes, hashed_value: Optional[bytes], siblings, other_key: Optional[bytes] = None,
              other_value_hash: Optional[bytes] = None) -> bytes:
    siblings = list(siblings)
    if len(siblings) > DEPTH:
        raise ValueError("a proof has at most %d siblings" % DEPTH)
    if (other_key is None) != (other_value_hash is None):
        raise ValueError("another leaf needs both its key and its value hash")
    depth = len(siblings)
    if hashed_value is not None:
        if other_key is not None:
            raise ValueError("a proof of a leaf cannot name another leaf")
        node = leaf_hash(key, hashed_value)
    elif other_key is not None:
        if other_key == key:
            raise ValueError("the other leaf of a proof of absence has the same key")
        if any(_bit(other_key, d) != _bit(key, d) for d in range(depth)):
            raise ValueError("the other leaf is not on the path of the key")
        node = leaf_hash(other_key, other_value_hash)
    else:
        node = EMPTY
    for d in range(depth - 1, -1, -1):
        sibling = siblings[d]
        node = _digest(node, sibling) if _bit(key, d) == 0 else _digest(sibling, node)
    return node


def index_signed_bytes(index: dict) -> bytes:
    return canonical_bytes({"format": INDEX_FORMAT, "sequence": index["sequence"], "headHash": index["headHash"],
                            "root": index["root"], "createdAt": format_instant(index["createdAt"]),
                            "keyId": index["keyId"]})


def verify_index_proof(proof: dict, pinned_public_key: Optional[str] = None, key_list=None,
                       package: Optional[dict] = None) -> IndexCheck:
    problems = []
    node = proof if isinstance(proof, dict) else {}
    index = _node(node, "index")
    sequence = _integer(index.get("sequence"))
    head_hash = _string(index.get("headHash"))
    key_id = _string(index.get("keyId"))
    leaf = node.get("leaf")
    present = isinstance(leaf, dict)
    count = _integer(leaf.get("count")) if present else None
    first = _integer(leaf.get("firstSequence")) if present else None
    last = _integer(leaf.get("lastSequence")) if present else None
    term = {}

    def check(valid=False):
        return IndexCheck(valid, term, present, count, first, last, sequence, head_hash, key_id, problems)

    if _string(node.get("format")) != INDEX_PROOF_FORMAT:
        problems.append("unknown index proof format %s" % _show(_string(node.get("format"))))
        return check()
    requested = {name: value if isinstance(value, str) else None for name, value in _node(node, "term").items()}
    try:
        term = index_term(requested)
    except ValueError as e:
        problems.append("the term is not valid: %s" % e)
        return check()
    key = index_key(term)
    if key.hex() != _string(node.get("key"), ""):
        problems.append("the key does not belong to the term")
    try:
        signed = _index(index, sequence, head_hash, key_id)
    except ValueError as e:
        problems.append("the index cannot be read: %s" % e)
        return check()
    trusted = trusted_package_keys(node, key_list, pinned_public_key, problems)
    signing = trusted.get(key_id)
    if signing is None:
        problems.append("the index is signed with key %s, which the proof does not trust" % key_id)
    elif _revoked(node, key_list, key_id):
        problems.append("the index is signed with revoked key %s" % key_id)
    elif not signing.verify_base64(signed, _string(index.get("signature"), "")):
        problems.append("the signature of the index is not valid")
    try:
        hashed_value = None
        if present:
            if (count is None or first is None or last is None or count < 1 or first < 1 or first > last
                    or last > sequence or count > last - first + 1):
                raise ValueError("the leaf is not a valid count with its first and last sequence")
            hashed_value = value_hash(index_value(count, first, last))
        elif leaf is not None:
            raise ValueError("the leaf must be an object or null")
        siblings = [_hash(_string(sibling, "")) for sibling in _items(node.get("siblings"))]
        other = node.get("otherLeaf")
        other_key = other_value_hash = None
        if isinstance(other, dict):
            other_key = _hash(_string(other.get("key"), ""))
            other_value_hash = _hash(_string(other.get("valueHash"), ""))
        if root_from(key, hashed_value, siblings, other_key, other_value_hash) != bytes.fromhex(index["root"]):
            problems.append("the proof does not lead to the signed root")
    except ValueError as e:
        problems.append("the proof cannot be read: %s" % e)
    if package is not None:
        checkpoint = _node(package if isinstance(package, dict) else {}, "checkpoint")
        if (sequence != _integer(checkpoint.get("sequence"), -1)
                or head_hash != _string(checkpoint.get("headHash"))):
            problems.append("the index is for another checkpoint than the package")
    return check(not problems)


def _index(index, sequence, head_hash, key_id) -> bytes:
    if _string(index.get("format")) != INDEX_FORMAT or sequence is None:
        raise ValueError("unknown index format %s" % _show(_string(index.get("format"))))
    created_at = _string(index.get("createdAt"), "")
    try:
        created_at = format_instant(parse_instant(created_at))
    except ValueError:
        raise ValueError("Text '%s' could not be parsed" % created_at) from None
    if key_id is None:
        raise ValueError("keyId")
    root = _string(index.get("root"))
    if not is_sha256(head_hash) or not is_sha256(root):
        raise ValueError("headHash and root must be lowercase hex SHA-256")
    return index_signed_bytes({"sequence": sequence, "headHash": head_hash, "root": root, "createdAt": created_at,
                               "keyId": key_id})


def _hash(text: str) -> bytes:
    if not is_sha256(text):
        raise ValueError("a hash must be a lowercase hex SHA-256")
    return bytes.fromhex(text)


def _bit(data: bytes, index: int) -> int:
    return (data[index // 8] >> (7 - index % 8)) & 1


def _digest(first: bytes, second: bytes) -> bytes:
    return hashlib.sha256(_NODE + first + second).digest()


def _revoked(proof, keys, key_id) -> bool:
    nodes = _items(proof.get("keys")) + _items(keys)
    return any(isinstance(node, dict) and node.get("keyId") == key_id and isinstance(node.get("revocation"), dict)
               for node in nodes)


def _node(node, name) -> dict:
    value = node.get(name)
    return value if isinstance(value, dict) else {}


def _items(value) -> list:
    if isinstance(value, dict):
        return list(value.values())
    return list(value) if isinstance(value, list) else []


def _string(value, default=None) -> Optional[str]:
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    return default


def _integer(value, default=None) -> Optional[int]:
    if isinstance(value, bool) or not isinstance(value, int):
        return default
    return value


def _show(value) -> str:
    return "null" if value is None else value


def _camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part.capitalize() for part in rest)
