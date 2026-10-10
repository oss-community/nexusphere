import base64
import binascii
import hashlib
import hmac
import json
from dataclasses import dataclass
from typing import Optional, Sequence

from . import merkle
from .keys import PublicKey
from .note import Note, NoteKey

KIND = "hashedrekord"
API_VERSION = "0.0.2"
KEY_DETAILS = "PKIX_ED25519_PH"


@dataclass(frozen=True)
class RekorVerification:
    valid: bool
    log_index: Optional[int] = None
    origin: Optional[str] = None
    pinned: bool = False
    problem: Optional[str] = None


def rekor_request(signed_bytes: bytes, signature: bytes, key: PublicKey) -> str:
    request = {"hashedRekordRequestV002": {
        "digest": base64.b64encode(hashlib.sha512(signed_bytes).digest()).decode("ascii"),
        "signature": {"content": base64.b64encode(signature).decode("ascii"),
                      "verifier": {"keyDetails": KEY_DETAILS, "publicKey": {"rawBytes": key.encoded}}}}}
    return json.dumps(request, separators=(",", ":"))


def verify_rekor_entry(entry: dict, signed_bytes: bytes, ledger_key: PublicKey,
                       rekor_keys: Sequence[NoteKey] = ()) -> RekorVerification:
    try:
        log_index = int(_text(entry, "logIndex"))
        body = base64.b64decode(_text(entry, "canonicalizedBody"), validate=True)
        proof = entry.get("inclusionProof") if isinstance(entry.get("inclusionProof"), dict) else {}
        if int(_text(proof, "logIndex")) != log_index:
            return RekorVerification(False, problem="the inclusion proof is for another entry")
        tree_size = int(_text(proof, "treeSize"))
        root = base64.b64decode(_text(proof, "rootHash"), validate=True)
        hashes = [base64.b64decode(h, validate=True) for h in proof.get("hashes") or []]
        note = Note.parse(_text(proof.get("checkpoint") or {}, "envelope"))
        parsed = json.loads(body)
        if parsed.get("kind") != KIND or parsed.get("apiVersion") != API_VERSION:
            return RekorVerification(False, problem="the entry is not a hashedrekord 0.0.2")
        spec = (parsed.get("spec") or {}).get("hashedRekordV002") or {}
    except (ValueError, TypeError, AttributeError, binascii.Error) as e:
        return RekorVerification(False, problem="the entry cannot be read: %s" % e)
    data = spec.get("data") if isinstance(spec.get("data"), dict) else {}
    digest = _decode(data.get("digest"))
    if (data.get("algorithm") != "SHA2_512" or digest is None
            or not hmac.compare_digest(digest, hashlib.sha512(signed_bytes).digest())):
        return RekorVerification(False, problem="the entry is for different data")
    verifier = ((spec.get("signature") or {}).get("verifier")) or {}
    key = _decode((verifier.get("publicKey") or {}).get("rawBytes"))
    if verifier.get("keyDetails") != KEY_DETAILS or key is None or not hmac.compare_digest(key, ledger_key.der):
        return RekorVerification(False, problem="the entry is signed by another key")
    if merkle.root_from_inclusion(merkle.leaf_hash(body), log_index, tree_size, hashes) != root:
        return RekorVerification(False, problem="the entry is not proven in the log")
    if note.checkpoint.size != tree_size or note.checkpoint.root != root:
        return RekorVerification(False, problem="the log checkpoint does not match the inclusion proof")
    keys = [NoteKey.parse(k) if isinstance(k, str) else k for k in rekor_keys or ()]
    if keys and not any(note.signed_by(k) for k in keys):
        return RekorVerification(False, problem="the log checkpoint is not signed by a pinned Rekor key")
    return RekorVerification(True, log_index, note.checkpoint.origin, bool(keys))


def _decode(value) -> Optional[bytes]:
    if not isinstance(value, str):
        return None
    try:
        return base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error):
        return None


def _text(node, field) -> str:
    value = node.get(field) if isinstance(node, dict) else None
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        raise ValueError(field + " is missing")
    return str(value)
