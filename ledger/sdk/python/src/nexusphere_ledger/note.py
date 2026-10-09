import base64
import hashlib
import struct
from dataclasses import dataclass
from typing import List, Optional

from .keys import PrivateKey, PublicKey

ED25519 = 0x01
COSIGNATURE = 0x04
SIGNATURE_PREFIX = "— "
_COSIGNATURE_HEADER = "cosignature/v1\ntime "


class NoteKey:

    def __init__(self, name: str, key_type: int, public_key: PublicKey):
        if not name or "+" in name or any(c.isspace() for c in name):
            raise ValueError("A note key name must be non-empty without spaces or '+'")
        if key_type not in (ED25519, COSIGNATURE):
            raise ValueError("Unsupported note key type %d" % key_type)
        self.name = name
        self.type = key_type
        self.public_key = public_key

    @classmethod
    def parse(cls, vkey: str) -> "NoteKey":
        parts = vkey.strip().split("+", 2)
        if len(parts) != 3:
            raise ValueError("A verifier key has the form name+hash+key")
        key = base64.b64decode(parts[2], validate=True)
        if len(key) != 33:
            raise ValueError("A verifier key holds a type byte and a 32-byte Ed25519 key")
        parsed = cls(parts[0], key[0], PublicKey.from_raw(key[1:]))
        if parsed.hash_hex != parts[1]:
            raise ValueError("The verifier key hash does not match its key")
        return parsed

    @property
    def hash(self) -> bytes:
        return hashlib.sha256(self.name.encode("utf-8") + b"\n" + bytes([self.type]) + self.public_key.raw).digest()[:4]

    @property
    def hash_hex(self) -> str:
        return self.hash.hex()

    @property
    def vkey(self) -> str:
        return self.name + "+" + self.hash_hex + "+" + base64.b64encode(
            bytes([self.type]) + self.public_key.raw).decode("ascii")


@dataclass(frozen=True)
class Signature:
    name: str
    key_hash: bytes
    signature: bytes

    def line(self) -> str:
        return SIGNATURE_PREFIX + self.name + " " + base64.b64encode(self.key_hash + self.signature).decode(
            "ascii") + "\n"

    @classmethod
    def parse(cls, line: str) -> "Signature":
        body = line[:-1] if line.endswith("\n") else line
        if not body.startswith(SIGNATURE_PREFIX):
            raise ValueError("A signature line starts with an em dash and a space")
        parts = body[len(SIGNATURE_PREFIX):].split(" ")
        if len(parts) != 2:
            raise ValueError("A signature line has a name and a signature")
        value = base64.b64decode(parts[1], validate=True)
        if len(value) < 5:
            raise ValueError("A signature is too short")
        return cls(parts[0], value[:4], value[4:])


@dataclass(frozen=True)
class LogCheckpoint:
    origin: str
    size: int
    root: bytes

    def __post_init__(self):
        if not self.origin or not self.origin.strip() or "\n" in self.origin:
            raise ValueError("A log origin is one non-empty line")
        if self.size < 0:
            raise ValueError("A tree size is not negative")
        if self.root is None or len(self.root) != 32:
            raise ValueError("A root hash has 32 bytes")

    def body(self) -> str:
        return "%s\n%d\n%s\n" % (self.origin, self.size, base64.b64encode(self.root).decode("ascii"))

    def sign(self, key: NoteKey, private_key: PrivateKey) -> "Note":
        if key.type != ED25519:
            raise ValueError("A checkpoint is signed with an Ed25519 note key")
        body = self.body()
        return Note(self, body, [Signature(key.name, key.hash, private_key.sign(body.encode("utf-8")))])


@dataclass(frozen=True)
class Note:
    checkpoint: LogCheckpoint
    body: str
    signatures: List[Signature]

    @classmethod
    def parse(cls, text: str) -> "Note":
        split = text.find("\n\n")
        if split < 0:
            raise ValueError("A note has a body, a blank line and signatures")
        body = text[:split + 1]
        lines = body.split("\n")
        if len(lines) < 4:
            raise ValueError("A checkpoint has an origin, a size and a root hash")
        checkpoint = LogCheckpoint(lines[0], int(lines[1]), base64.b64decode(lines[2], validate=True))
        if checkpoint.body() != body:
            raise ValueError("Checkpoint extension lines are not supported")
        signatures = [Signature.parse(line) for line in text[split + 2:].split("\n") if line]
        if not signatures:
            raise ValueError("A note has at least one signature")
        return cls(checkpoint, body, signatures)

    def text(self) -> str:
        return self.body + "\n" + "".join(signature.line() for signature in self.signatures)

    def with_signature(self, signature: Signature) -> "Note":
        return Note(self.checkpoint, self.body, list(self.signatures) + [signature])

    def signed_by(self, key: NoteKey) -> bool:
        return any(self._verify(key, signature) for signature in self.signatures)

    def cosigned_by(self, key: NoteKey) -> Optional[int]:
        for signature in self.signatures:
            time = self._cosignature_time(key, signature)
            if time is not None:
                return time
        return None

    def _verify(self, key: NoteKey, signature: Signature) -> bool:
        return (key.type == ED25519 and key.name == signature.name and key.hash == signature.key_hash
                and key.public_key.verify(self.body.encode("utf-8"), signature.signature))

    def _cosignature_time(self, key: NoteKey, signature: Signature) -> Optional[int]:
        if (key.type != COSIGNATURE or key.name != signature.name or key.hash != signature.key_hash
                or len(signature.signature) != 72):
            return None
        time = struct.unpack(">Q", signature.signature[:8])[0]
        if key.public_key.verify(_cosigned_bytes(time, self.body), signature.signature[8:]):
            return time
        return None


def cosign(body: str, key: NoteKey, private_key: PrivateKey, time: int) -> Signature:
    if key.type != COSIGNATURE:
        raise ValueError("A cosignature uses a cosignature key")
    return Signature(key.name, key.hash, struct.pack(">Q", time) + private_key.sign(_cosigned_bytes(time, body)))


def _cosigned_bytes(time: int, body: str) -> bytes:
    return (_COSIGNATURE_HEADER + str(time) + "\n" + body).encode("utf-8")
