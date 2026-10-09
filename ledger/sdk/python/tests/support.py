import json
import os
from pathlib import Path

from nexusphere_ledger import merkle
from nexusphere_ledger.cose import EvidenceStatement, LogReceipt
from nexusphere_ledger.evidence import GENESIS, Checkpoint, content_hash, link_hash
from nexusphere_ledger.keys import PrivateKey
from nexusphere_ledger.note import COSIGNATURE, ED25519, LogCheckpoint, NoteKey, cosign

VECTORS = Path(os.environ.get("NEXUSPHERE_VECTORS", Path(__file__).resolve().parents[3] / "conformance" / "vectors"))

LEDGER_KEY = PrivateKey.from_seed(bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))
WITNESS_KEY = PrivateKey.from_seed(bytes.fromhex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"))
ISSUER = "https://ledger.example"
ORIGIN = "ledger.example"
WITNESS = "witness.example"


def vector(name: str):
    with open(VECTORS / name, encoding="utf-8") as file:
        return json.load(file)


def witness_note_key() -> NoteKey:
    return NoteKey(WITNESS, COSIGNATURE, WITNESS_KEY.public_key)


def entries(count: int, agents=("invoice-agent", "sales-agent"), principals=("acme", "globex")) -> list:
    chain = []
    previous = GENESIS
    for index in range(count):
        sequence = index + 1
        entry = {
            "format": "nexusphere-ledger/evidence/v1",
            "id": "00000000-0000-4000-8000-%012d" % sequence,
            "sequence": sequence,
            "occurredAt": "2026-10-01T08:00:%02d.123456Z" % index,
            "recordedAt": "2026-10-01T08:00:%02d.128456Z" % index,
            "agentId": agents[index % len(agents)],
            "principalId": principals[index % len(principals)],
            "action": "tools/call",
            "target": "read_invoice",
            "decision": "ALLOW",
            "reason": None,
            "delegationId": None,
            "inputHash": None,
            "outputHash": None,
            "outcome": "SUCCEEDED",
            "correlationId": None,
            "attributes": {"tool": "read_invoice"},
            "previousHash": previous,
        }
        entry["hash"] = link_hash(sequence, previous, content_hash(entry))
        previous = entry["hash"]
        chain.append(entry)
    return chain


def checkpoint(sequence: int, head_hash: str, key: PrivateKey = LEDGER_KEY,
               created_at: str = "2026-10-01T09:00:00.000001Z") -> dict:
    signed = Checkpoint(sequence, head_hash, created_at, key.key_id).sign(key)
    return {"format": "nexusphere-ledger/checkpoint/v1", "sequence": sequence, "headHash": head_hash,
            "createdAt": created_at, "keyId": key.key_id, "signature": signed.signature}


def key_record(key: PrivateKey, status: str = "ACTIVE", rotation=None) -> dict:
    record = {"keyId": key.key_id, "algorithm": "Ed25519", "publicKey": key.public_key.encoded, "status": status,
              "activatedAt": "2026-10-01T07:00:00Z", "retiredAt": None, "rotation": None}
    if rotation is not None:
        record["activatedAt"] = rotation.activated_at
        record["rotation"] = {"format": "nexusphere-ledger/key-rotation/v1", "previousKeyId": rotation.previous_key_id,
                              "keySignature": rotation.key_signature,
                              "previousKeySignature": rotation.previous_key_signature}
    return record


def package(chain, disclose, key: PrivateKey = LEDGER_KEY, keys=None, anchor: int = 0, scope=None,
            witness: bool = False, log: bool = True) -> dict:
    size = len(chain)
    leaves = [merkle.leaf_hash(bytes.fromhex(e["hash"])) for e in chain]
    links = []
    for entry in chain[anchor:]:
        link = {"sequence": entry["sequence"], "previousHash": entry["previousHash"],
                "contentHash": content_hash(entry), "hash": entry["hash"], "entry": None}
        if entry["sequence"] in disclose:
            link["entry"] = dict(entry)
        links.append(link)
    pkg = {
        "format": "nexusphere-ledger/package/v1",
        "createdAt": "2026-10-01T09:00:01Z",
        "scope": scope or {"agentId": None, "principalId": None, "fromSequence": 1, "toSequence": size},
        "keys": keys if keys is not None else [key_record(key)],
        "anchor": checkpoint(anchor, chain[anchor - 1]["hash"], key) if anchor else None,
        "checkpoint": checkpoint(size, chain[-1]["hash"], key),
        "disclosed": len(disclose),
        "links": links,
        "log": None,
    }
    if log:
        root = merkle.root(leaves)
        note = LogCheckpoint(ORIGIN, size, root).sign(NoteKey(ORIGIN, ED25519, key.public_key), key)
        if witness:
            note = note.with_signature(cosign(note.body, witness_note_key(), WITNESS_KEY, 1760000000))
        proofs = []
        for sequence in sorted(disclose):
            entry = chain[sequence - 1]
            path = merkle.inclusion_proof(leaves, sequence - 1)
            import base64
            proofs.append({
                "sequence": sequence,
                "hashes": [base64.b64encode(h).decode() for h in path],
                "statement": base64.b64encode(EvidenceStatement.sign(entry, ISSUER, key.key_id, key)).decode(),
                "receipt": base64.b64encode(LogReceipt.sign(ORIGIN, key.key_id, size, sequence - 1, path, root,
                                                            key)).decode(),
            })
        pkg["log"] = {"checkpoint": note.text(), "proofs": proofs}
    return pkg
