import re
import uuid
from dataclasses import dataclass
from typing import Optional

from .canonical import canonical_bytes, sha256_hex
from .keys import PrivateKey, PublicKey
from .timestamps import format_instant

FORMAT = "nexusphere-ledger/evidence/v1"
CHECKPOINT_FORMAT = "nexusphere-ledger/checkpoint/v1"
CHECKPOINT_FORMAT_WITH_PROFILES = "nexusphere-ledger/checkpoint/v2"
GENESIS = "0" * 64

_SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")

CONTENT_FIELDS = ("agentId", "principalId", "action", "target", "decision", "reason", "delegationId", "inputHash",
                  "outputHash", "outcome", "correlationId")


def is_sha256(value) -> bool:
    return isinstance(value, str) and _SHA256_HEX.match(value) is not None


def canonical_content(entry: dict) -> dict:
    content = {
        "format": FORMAT,
        "id": str(uuid.UUID(_required(entry, "id"))),
        "occurredAt": format_instant(_required(entry, "occurredAt")),
        "recordedAt": format_instant(_required(entry, "recordedAt")),
    }
    for field in CONTENT_FIELDS:
        value = entry.get(field)
        content[field] = value if isinstance(value, str) else None
    content["attributes"] = {key: _text(value) for key, value in (entry.get("attributes") or {}).items()}
    return content


def content_hash(entry: dict) -> str:
    return sha256_hex(canonical_bytes(canonical_content(entry)))


def link_hash(sequence: int, previous_hash: str, content_hash: str) -> str:
    return sha256_hex(canonical_bytes({
        "format": FORMAT,
        "sequence": sequence,
        "previousHash": previous_hash,
        "contentHash": content_hash,
    }))


def entry_hash(entry: dict) -> str:
    return link_hash(entry["sequence"], entry["previousHash"], content_hash(entry))


@dataclass(frozen=True)
class EvidenceLink:
    sequence: int
    previous_hash: str
    content_hash: str
    hash: str

    @classmethod
    def of(cls, node: dict) -> "EvidenceLink":
        return cls(_long(node.get("sequence")), _required(node, "previousHash"), _required(node, "contentHash"),
                   _required(node, "hash"))

    @classmethod
    def of_entry(cls, entry: dict) -> "EvidenceLink":
        return cls(_long(entry.get("sequence")), _required(entry, "previousHash"), content_hash(entry),
                   _required(entry, "hash"))

    def compute_hash(self) -> str:
        return link_hash(self.sequence, self.previous_hash, self.content_hash)


@dataclass(frozen=True)
class ChainVerification:
    valid: bool
    checked_entries: int
    last_sequence: int
    last_hash: str
    failed_sequence: Optional[int] = None
    failure: Optional[str] = None


class ChainVerifier:

    def __init__(self, first_sequence: int = 1, previous_hash: str = GENESIS):
        self._expected_sequence = first_sequence
        self._expected_previous_hash = previous_hash
        self._checked = 0
        self._failure = None

    def accept(self, link) -> bool:
        if isinstance(link, dict):
            link = EvidenceLink.of_entry(link)
        if self._failure is not None:
            return False
        if link.sequence != self._expected_sequence:
            return self._fail(link, "expected sequence %d but found %d" % (self._expected_sequence, link.sequence))
        if self._expected_previous_hash != link.previous_hash:
            return self._fail(link, "previous hash does not match the hash of sequence %d"
                              % (self._expected_sequence - 1))
        recomputed = link.compute_hash()
        if recomputed != link.hash:
            return self._fail(link, "content does not match its hash")
        self._checked += 1
        self._expected_sequence += 1
        self._expected_previous_hash = recomputed
        return True

    def result(self) -> ChainVerification:
        if self._failure is not None:
            return self._failure
        return ChainVerification(True, self._checked, self._expected_sequence - 1, self._expected_previous_hash)

    def _fail(self, link, reason) -> bool:
        self._failure = ChainVerification(False, self._checked, self._expected_sequence - 1,
                                          self._expected_previous_hash, link.sequence, reason)
        return False


def verify_chain(entries) -> ChainVerification:
    verifier = ChainVerifier()
    for entry in entries:
        if not verifier.accept(entry):
            break
    return verifier.result()


@dataclass(frozen=True)
class Checkpoint:
    sequence: int
    head_hash: str
    created_at: str
    key_id: str
    signature: Optional[str] = None
    profiles: Optional[tuple] = None

    @classmethod
    def of(cls, node: dict) -> "Checkpoint":
        profiles = node.get("profiles")
        if profiles is not None:
            if not isinstance(profiles, list):
                raise ValueError("profiles must be a list")
            profiles = tuple((_required(p, "id"), _required(p, "digest")) for p in profiles)
        return cls(_long(node.get("sequence")), _required(node, "headHash"),
                   format_instant(_required(node, "createdAt")), _required(node, "keyId"), node.get("signature"),
                   profiles)

    @property
    def format(self) -> str:
        return CHECKPOINT_FORMAT if self.profiles is None else CHECKPOINT_FORMAT_WITH_PROFILES

    def signed_bytes(self) -> bytes:
        content = {
            "format": self.format,
            "sequence": self.sequence,
            "headHash": self.head_hash,
            "createdAt": format_instant(self.created_at),
            "keyId": self.key_id,
        }
        if self.profiles is not None:
            content["profiles"] = [{"id": i, "digest": d} for i, d in sorted(self.profiles)]
        return canonical_bytes(content)

    def sign(self, key: PrivateKey) -> "Checkpoint":
        return Checkpoint(self.sequence, self.head_hash, self.created_at, self.key_id,
                          key.sign_base64(self.signed_bytes()), self.profiles)

    def verify(self, key: PublicKey) -> bool:
        return (self.signature is not None and key.key_id == self.key_id
                and key.verify_base64(self.signed_bytes(), self.signature))


def _required(node: dict, field: str) -> str:
    value = node.get(field) if isinstance(node, dict) else None
    if not isinstance(value, str):
        raise ValueError(field + " is missing")
    return value


def _long(value) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return 0
    return int(value)


def _text(value) -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return ""
    return str(value)
