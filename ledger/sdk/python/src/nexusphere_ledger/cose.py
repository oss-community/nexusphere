from dataclasses import dataclass
from typing import List, Optional

from . import cbor, merkle
from .evidence import link_hash
from .keys import PrivateKey, PublicKey

TAG = 18
ALG = 1
KID = 4
CWT_CLAIMS = 15
VDS = 395
VDP = 396
PAYLOAD_HASH_ALG = 258
PREIMAGE_CONTENT_TYPE = 259
EDDSA = -8
SHA_256 = -16
ISS = 1
SUB = 2

CONTENT_TYPE = "application/vnd.nexusphere.evidence+json"
SEQUENCE = "nexusphere-sequence"
PREVIOUS_HASH = "nexusphere-previous-hash"
RFC9162_SHA256 = 1
INCLUSION_PROOFS = -1


def _is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


@dataclass(frozen=True)
class CoseSign1:
    protected_bytes: bytes
    protected_header: dict
    unprotected: dict
    payload: Optional[bytes]
    signature: bytes

    @staticmethod
    def sign(protected_header: dict, unprotected: dict, payload: bytes, detached: bool, key: PrivateKey) -> bytes:
        protected_bytes = cbor.encode(protected_header)
        signature = key.sign(_to_be_signed(protected_bytes, payload))
        return cbor.encode(cbor.Tagged(TAG, [protected_bytes, unprotected, None if detached else payload,
                                             signature]))

    @classmethod
    def parse(cls, data: bytes) -> "CoseSign1":
        decoded = cbor.decode(data)
        if isinstance(decoded, cbor.Tagged) and decoded.tag == TAG:
            decoded = decoded.value
        if (not isinstance(decoded, list) or len(decoded) != 4 or not isinstance(decoded[0], bytes)
                or not isinstance(decoded[1], dict) or not isinstance(decoded[3], bytes)
                or not (decoded[2] is None or isinstance(decoded[2], bytes))):
            raise ValueError("Not a COSE_Sign1 message")
        header = {} if len(decoded[0]) == 0 else cbor.decode(decoded[0])
        if not isinstance(header, dict):
            raise ValueError("The COSE protected header is not a map")
        return cls(decoded[0], header, decoded[1], decoded[2], decoded[3])

    def verify(self, key: PublicKey, detached_payload: Optional[bytes] = None) -> bool:
        signed = self.payload if self.payload is not None else detached_payload
        if signed is None or self.protected_header.get(ALG) != EDDSA:
            return False
        return key.verify(_to_be_signed(self.protected_bytes, signed), self.signature)

    @property
    def key_id(self) -> Optional[str]:
        kid = self.protected_header.get(KID)
        return kid.decode("ascii") if isinstance(kid, bytes) else None

    @property
    def claims(self) -> dict:
        claims = self.protected_header.get(CWT_CLAIMS)
        return claims if isinstance(claims, dict) else {}


def _to_be_signed(protected_bytes: bytes, payload: bytes) -> bytes:
    return cbor.encode(["Signature1", protected_bytes, b"", payload])


@dataclass(frozen=True)
class EvidenceStatement:
    message: CoseSign1
    issuer: str
    subject: str
    sequence: int
    previous_hash: str
    content_hash: str

    @staticmethod
    def sign(entry: dict, issuer: str, key_id: str, key: PrivateKey, content_hash: Optional[str] = None) -> bytes:
        from .evidence import content_hash as compute
        header = {
            ALG: EDDSA,
            KID: key_id.encode("ascii"),
            CWT_CLAIMS: {ISS: issuer, SUB: subject_of(entry)},
            PAYLOAD_HASH_ALG: SHA_256,
            PREIMAGE_CONTENT_TYPE: CONTENT_TYPE,
            SEQUENCE: int(entry["sequence"]),
            PREVIOUS_HASH: bytes.fromhex(entry["previousHash"]),
        }
        return CoseSign1.sign(header, {}, bytes.fromhex(content_hash or compute(entry)), False, key)

    @classmethod
    def parse(cls, data: bytes) -> "EvidenceStatement":
        message = CoseSign1.parse(data)
        header = message.protected_header
        claims = message.claims
        previous = header.get(PREVIOUS_HASH)
        if (header.get(PAYLOAD_HASH_ALG) != SHA_256 or header.get(PREIMAGE_CONTENT_TYPE) != CONTENT_TYPE
                or not _is_int(header.get(SEQUENCE)) or not isinstance(previous, bytes) or len(previous) != 32
                or not isinstance(claims.get(ISS), str) or not isinstance(claims.get(SUB), str)
                or message.payload is None or len(message.payload) != 32):
            raise ValueError("Not a Nexusphere evidence statement")
        return cls(message, claims[ISS], claims[SUB], header[SEQUENCE], previous.hex(), message.payload.hex())

    def verify(self, key: PublicKey) -> bool:
        return self.message.verify(key)

    @property
    def key_id(self) -> Optional[str]:
        return self.message.key_id

    @property
    def entry_hash(self) -> str:
        return link_hash(self.sequence, self.previous_hash, self.content_hash)

    @property
    def leaf_hash(self) -> bytes:
        return merkle.leaf_hash(bytes.fromhex(self.entry_hash))

    def describes(self, link) -> bool:
        return (link.sequence == self.sequence and link.previous_hash == self.previous_hash
                and link.content_hash == self.content_hash)


def subject_of(entry: dict) -> str:
    return "urn:uuid:" + str(entry["id"])


@dataclass(frozen=True)
class LogReceipt:
    message: CoseSign1
    issuer: str
    tree_size: int
    leaf_index: int
    path: List[bytes]

    @staticmethod
    def sign(issuer: str, key_id: str, tree_size: int, leaf_index: int, path: List[bytes], root: bytes,
             key: PrivateKey) -> bytes:
        header = {
            ALG: EDDSA,
            KID: key_id.encode("ascii"),
            VDS: RFC9162_SHA256,
            CWT_CLAIMS: {ISS: issuer},
        }
        proof = cbor.encode([tree_size, leaf_index, list(path)])
        return CoseSign1.sign(header, {VDP: {INCLUSION_PROOFS: [proof]}}, root, True, key)

    @classmethod
    def parse(cls, data: bytes) -> "LogReceipt":
        message = CoseSign1.parse(data)
        proofs = message.unprotected.get(VDP)
        inclusion = proofs.get(INCLUSION_PROOFS) if isinstance(proofs, dict) else None
        if (message.protected_header.get(VDS) != RFC9162_SHA256 or not isinstance(message.claims.get(ISS), str)
                or not isinstance(inclusion, list) or len(inclusion) != 1 or not isinstance(inclusion[0], bytes)):
            raise ValueError("Not an RFC 9162 inclusion receipt")
        proof = cbor.decode(inclusion[0])
        if (not isinstance(proof, list) or len(proof) != 3 or not _is_int(proof[0]) or not _is_int(proof[1])
                or not isinstance(proof[2], list)):
            raise ValueError("Not an RFC 9162 inclusion receipt")
        for item in proof[2]:
            if not isinstance(item, bytes) or len(item) != 32:
                raise ValueError("Not an RFC 9162 inclusion receipt")
        return cls(message, message.claims[ISS], proof[0], proof[1], list(proof[2]))

    def root(self, leaf_hash: bytes) -> Optional[bytes]:
        return merkle.root_from_inclusion(leaf_hash, self.leaf_index, self.tree_size, self.path)

    def verify(self, leaf_hash: bytes, key: PublicKey) -> bool:
        computed = self.root(leaf_hash)
        return computed is not None and self.message.verify(key, computed)

    @property
    def key_id(self) -> Optional[str]:
        return self.message.key_id
