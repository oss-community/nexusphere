import base64
import hashlib

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

from .canonical import canonical_bytes
from .timestamps import format_instant

ALGORITHM = "Ed25519"
ROTATION_FORMAT = "nexusphere-ledger/key-rotation/v1"


class PublicKey:

    def __init__(self, key: Ed25519PublicKey):
        self._key = key
        self.der = key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        self.raw = key.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        self.key_id = hashlib.sha256(self.der).hexdigest()[:16]

    @classmethod
    def from_base64(cls, text: str) -> "PublicKey":
        try:
            key = serialization.load_der_public_key(base64.b64decode(text.strip(), validate=True))
        except Exception as e:
            raise ValueError("Not a base64 X.509 Ed25519 public key") from e
        if not isinstance(key, Ed25519PublicKey):
            raise ValueError("Not a base64 X.509 Ed25519 public key")
        return cls(key)

    @classmethod
    def from_raw(cls, raw: bytes) -> "PublicKey":
        if len(raw) != 32:
            raise ValueError("An Ed25519 key has 32 bytes")
        return cls(Ed25519PublicKey.from_public_bytes(raw))

    @property
    def encoded(self) -> str:
        return base64.b64encode(self.der).decode("ascii")

    def verify(self, data: bytes, signature: bytes) -> bool:
        try:
            self._key.verify(signature, data)
            return True
        except (InvalidSignature, ValueError, TypeError):
            return False

    def verify_base64(self, data: bytes, signature: str) -> bool:
        try:
            raw = base64.b64decode(signature, validate=True)
        except (ValueError, TypeError):
            return False
        return self.verify(data, raw)

    def __eq__(self, other):
        return isinstance(other, PublicKey) and self.der == other.der

    def __hash__(self):
        return hash(self.der)

    def __repr__(self):
        return "PublicKey(" + self.key_id + ")"


class PrivateKey:

    def __init__(self, key: Ed25519PrivateKey):
        self._key = key
        self.public_key = PublicKey(key.public_key())

    @classmethod
    def generate(cls) -> "PrivateKey":
        return cls(Ed25519PrivateKey.generate())

    @classmethod
    def from_seed(cls, seed: bytes) -> "PrivateKey":
        return cls(Ed25519PrivateKey.from_private_bytes(seed))

    @classmethod
    def from_base64(cls, text: str) -> "PrivateKey":
        try:
            key = serialization.load_der_private_key(base64.b64decode(text.strip(), validate=True), password=None)
        except Exception as e:
            raise ValueError("Not a base64 PKCS#8 Ed25519 private key") from e
        if not isinstance(key, Ed25519PrivateKey):
            raise ValueError("Not a base64 PKCS#8 Ed25519 private key")
        return cls(key)

    @property
    def encoded(self) -> str:
        der = self._key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
                                      serialization.NoEncryption())
        return base64.b64encode(der).decode("ascii")

    @property
    def key_id(self) -> str:
        return self.public_key.key_id

    def sign(self, data: bytes) -> bytes:
        return self._key.sign(data)

    def sign_base64(self, data: bytes) -> str:
        return base64.b64encode(self.sign(data)).decode("ascii")


class KeyRotation:

    def __init__(self, key_id, public_key, previous_key_id, activated_at, key_signature,
                 previous_key_signature=None):
        if None in (key_id, public_key, previous_key_id, activated_at, key_signature):
            raise ValueError("A key rotation needs keyId, publicKey, previousKeyId, activatedAt and keySignature")
        self.key_id = key_id
        self.public_key = public_key
        self.previous_key_id = previous_key_id
        self.activated_at = format_instant(activated_at)
        self.key_signature = key_signature
        self.previous_key_signature = previous_key_signature

    @classmethod
    def issue(cls, key: PrivateKey, previous_key_id: str, previous_key: PrivateKey, activated_at) -> "KeyRotation":
        content = signed_rotation_bytes(key.key_id, key.public_key.encoded, previous_key_id, activated_at)
        return cls(key.key_id, key.public_key.encoded, previous_key_id, activated_at, key.sign_base64(content),
                   None if previous_key is None else previous_key.sign_base64(content))

    @property
    def endorsed(self) -> bool:
        return self.previous_key_signature is not None

    def key(self):
        try:
            key = PublicKey.from_base64(self.public_key)
        except ValueError:
            return None
        return key if key.key_id == self.key_id else None

    def verified_by_key(self) -> bool:
        key = self.key()
        return key is not None and key.verify_base64(self._content(), self.key_signature)

    def verified_by_previous(self, previous: PublicKey) -> bool:
        return (self.endorsed and previous.key_id == self.previous_key_id and self.verified_by_key()
                and previous.verify_base64(self._content(), self.previous_key_signature))

    def _content(self) -> bytes:
        return signed_rotation_bytes(self.key_id, self.public_key, self.previous_key_id, self.activated_at)


def signed_rotation_bytes(key_id, public_key, previous_key_id, activated_at) -> bytes:
    return canonical_bytes({
        "format": ROTATION_FORMAT,
        "keyId": key_id,
        "algorithm": ALGORITHM,
        "publicKey": public_key,
        "previousKeyId": previous_key_id,
        "activatedAt": format_instant(activated_at),
    })


def trusted_keys(pinned: PublicKey, keys, rotations) -> dict:
    known = {key.key_id: key for key in keys}
    trusted = {pinned.key_id: pinned}
    changed = True
    while changed:
        changed = False
        for rotation in rotations:
            if not rotation.verified_by_key():
                continue
            previous = known.get(rotation.previous_key_id)
            if rotation.key_id in trusted and previous is not None and previous.key_id not in trusted:
                trusted[previous.key_id] = previous
                changed = True
            endorser = trusted.get(rotation.previous_key_id)
            if endorser is not None and rotation.key_id not in trusted and rotation.verified_by_previous(endorser):
                trusted[rotation.key_id] = rotation.key()
                changed = True
    return trusted
