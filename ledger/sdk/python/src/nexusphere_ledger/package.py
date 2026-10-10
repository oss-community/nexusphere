import base64
from dataclasses import asdict, dataclass, field
from typing import List, Optional

from . import merkle
from .cose import EvidenceStatement, LogReceipt
from .evidence import GENESIS, ChainVerifier, Checkpoint, EvidenceLink, content_hash, erased
from .keys import KeyRevocation, KeyRotation, PublicKey, revoked_keys, trusted_and_revoked
from .note import ED25519, Note, NoteKey

FORMAT = "nexusphere-ledger/package/v1"


@dataclass(frozen=True)
class PackageReport:
    valid: bool
    key_id: Optional[str]
    pinned_key_id: Optional[str]
    anchor_sequence: Optional[int]
    checkpoint_sequence: int
    checkpoint_created_at: Optional[str]
    compliance_profiles: List[dict]
    first_sequence: int
    checked_links: int
    disclosed_entries: int
    agent_id: Optional[str]
    principal_id: Optional[str]
    log_origin: Optional[str]
    log_tree_size: Optional[int]
    proven_entries: int
    receipted_entries: int
    witnesses: List[str] = field(default_factory=list)
    revoked_keys: List[str] = field(default_factory=list)
    problems: List[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {_camel(key): value for key, value in asdict(self).items()}


@dataclass(frozen=True)
class _Log:
    origin: Optional[str] = None
    size: Optional[int] = None
    proven: int = 0
    receipted: int = 0
    witnesses: tuple = ()
    cosigned_at: tuple = ()
    signers: tuple = ()


def verify_package(pkg: dict, public_key: Optional[str] = None, witness_keys=(),
                   required_witnesses: Optional[int] = None, key_list=None) -> PackageReport:
    witness_keys = [NoteKey.parse(key) if isinstance(key, str) else key for key in witness_keys]
    required = len(witness_keys) if required_witnesses is None else required_witnesses
    problems = []
    scope = pkg.get("scope") if isinstance(pkg.get("scope"), dict) else {}
    if pkg.get("format") != FORMAT:
        problems.append("unknown package format %s" % _show(pkg.get("format")))
        return _report(False, None, None, None, 0, None, None, 0, 0, 0, scope, _Log(), [], problems)
    try:
        checkpoint = Checkpoint.of(_object(pkg.get("checkpoint")))
        anchor = Checkpoint.of(pkg["anchor"]) if isinstance(pkg.get("anchor"), dict) else None
    except (ValueError, KeyError, TypeError) as e:
        problems.append("the checkpoints cannot be read: %s" % e)
        return _report(False, None, None, None, 0, None, None, 0, 0, 0, scope, _Log(), [], problems)
    found = _keys(pkg, key_list, public_key, problems)
    keys, revoked = (None, {}) if found is None else found
    pinned = public_key is not None
    key = None if keys is None else _key(keys, checkpoint, pinned, problems)
    if key is not None and not checkpoint.verify(key):
        problems.append("the signature of checkpoint %d is not valid" % checkpoint.sequence)
    anchor_key = None if keys is None or anchor is None else _key(keys, anchor, pinned, problems)
    if anchor_key is not None and not anchor.verify(anchor_key):
        problems.append("the signature of anchor checkpoint %d is not valid" % anchor.sequence)
    first = 1 if anchor is None else anchor.sequence + 1
    chain = ChainVerifier(first, GENESIS if anchor is None else anchor.head_hash)
    disclosed = 0
    agent_id = _nullable(scope, "agentId")
    principal_id = _nullable(scope, "principalId")
    low = _long(scope.get("fromSequence"), 1)
    high = _long(scope.get("toSequence"), 2 ** 63 - 1)
    links = pkg.get("links") if isinstance(pkg.get("links"), list) else []
    unreadable = False
    for node in links:
        try:
            link = EvidenceLink.of(_object(node))
        except (ValueError, TypeError) as e:
            problems.append("a link cannot be read: %s" % e)
            unreadable = True
            break
        if isinstance(node.get("entry"), dict):
            disclosed += 1
            mismatch = _disclosed_mismatch(node["entry"], link, agent_id, principal_id, low, high)
            if mismatch is not None:
                problems.append("sequence %d: %s" % (link.sequence, mismatch))
                break
        if not chain.accept(link):
            break
    result = chain.result()
    if not result.valid:
        problems.append("sequence %d: %s" % (result.failed_sequence, result.failure))
    elif not problems and (result.last_sequence != checkpoint.sequence or result.last_hash != checkpoint.head_hash):
        problems.append("the chain ends at sequence %d and does not reach the signed head of checkpoint %d"
                        % (result.last_sequence, checkpoint.sequence))
    if disclosed == 0 and not unreadable:
        problems.append("the package discloses no evidence")
    log = _Log() if keys is None else _log(pkg, list(keys.values()), checkpoint.sequence, witness_keys, required,
                                           problems)
    used = [checkpoint.key_id] + ([] if anchor is None else [anchor.key_id]) + list(log.signers)
    revoked_used = _revoked(revoked, used, log, max(1, required), problems)
    return _report(not problems, checkpoint.key_id, _pinned_key_id(public_key),
                   None if anchor is None else anchor.sequence, checkpoint.sequence, checkpoint.created_at,
                   checkpoint.profiles, first,
                   result.checked_entries, disclosed, scope, log, revoked_used, problems)


def _revoked(revoked, used, log, required, problems) -> list:
    found = []
    for key_id in dict.fromkeys(used):
        revocation = revoked.get(key_id)
        if revocation is None:
            continue
        found.append(key_id)
        before = sum(1 for _, time in log.cosigned_at if time < revocation.compromised_epoch_second)
        if before < required:
            problems.append("key %s was revoked as compromised from %s, and %d of the %d required witness "
                            "cosignatures prove that the log checkpoint was made before then"
                            % (key_id, _java_instant(revocation.compromised_at), before, required))
    return found


def _log(pkg, keys, size, witness_keys, required, problems) -> _Log:
    log = pkg.get("log")
    if not isinstance(log, dict):
        if required > 0:
            problems.append("the package has no log checkpoint for witnesses to cosign")
        return _Log()
    try:
        note = Note.parse(_text(log, "checkpoint"))
    except (ValueError, TypeError) as e:
        problems.append("the log checkpoint cannot be read: %s" % e)
        return _Log()
    checkpoint = note.checkpoint
    signers = [key.key_id for key in keys if note.signed_by(NoteKey(checkpoint.origin, ED25519, key))]
    if not signers:
        problems.append("the log checkpoint is not signed by a trusted ledger key")
    if checkpoint.size != size:
        problems.append("the log checkpoint covers %d entries, not the %d of the signed checkpoint"
                        % (checkpoint.size, size))
    proofs = {}
    scitt = {}
    for proof in log.get("proofs") or []:
        sequence = _long(proof.get("sequence"), 0)
        try:
            proofs[sequence] = [base64.b64decode(item, validate=True) for item in proof.get("hashes") or []]
        except (ValueError, TypeError):
            proofs[sequence] = None
        scitt[sequence] = proof
    proven = 0
    receipted = 0
    for node in pkg.get("links") or []:
        if not isinstance(node, dict) or not isinstance(node.get("entry"), dict):
            continue
        sequence = _long(node.get("sequence"), 0)
        hashes = proofs.get(sequence)
        try:
            link = EvidenceLink.of(node)
            leaf = merkle.leaf_hash(bytes.fromhex(link.hash))
        except (ValueError, TypeError):
            problems.append("sequence %d is not proven to be in the log checkpoint" % sequence)
            continue
        if hashes is None or not merkle.verify_inclusion(leaf, sequence - 1, checkpoint.size, hashes,
                                                         checkpoint.root):
            problems.append("sequence %d is not proven to be in the log checkpoint" % sequence)
        else:
            proven += 1
        statement = scitt.get(sequence)
        if statement is not None and "statement" in statement:
            problem = _scitt_problem(statement, link, keys, checkpoint, signers)
            if problem is None:
                receipted += 1
            else:
                problems.append("sequence %d %s" % (sequence, problem))
    cosigned_at = {}
    for key in witness_keys:
        time = note.cosigned_by(key)
        if time is not None and key.name not in cosigned_at:
            cosigned_at[key.name] = time
    cosigned = list(cosigned_at)
    if len(cosigned) < required:
        problems.append("the log checkpoint is cosigned by %d of the %d required witnesses" % (len(cosigned),
                                                                                               required))
    return _Log(checkpoint.origin, checkpoint.size, proven, receipted, tuple(cosigned),
                tuple(cosigned_at.items()), tuple(dict.fromkeys(signers)))


def _scitt_problem(signed, link, keys, checkpoint, signers) -> Optional[str]:
    try:
        statement = EvidenceStatement.parse(base64.b64decode(_text(signed, "statement"), validate=True))
        receipt = LogReceipt.parse(base64.b64decode(_text(signed, "receipt"), validate=True))
    except (ValueError, TypeError) as e:
        return "has a statement or receipt that cannot be read: %s" % e
    statement_key = next((key for key in keys if key.key_id == statement.key_id), None)
    receipt_key = next((key for key in keys if key.key_id == receipt.key_id), None)
    if statement_key is None or not statement.verify(statement_key):
        return "has a statement that is not signed by a trusted ledger key"
    signers.append(statement_key.key_id)
    if not statement.describes(link):
        return "has a statement for different evidence"
    if receipt_key is None or not receipt.verify(statement.leaf_hash, receipt_key):
        return "has a receipt that does not prove its statement with a trusted ledger key"
    signers.append(receipt_key.key_id)
    if receipt.tree_size != checkpoint.size or receipt.root(statement.leaf_hash) != checkpoint.root:
        return "has a receipt for a different log checkpoint"
    return None


def _disclosed_mismatch(entry, link, agent_id, principal_id, low, high) -> Optional[str]:
    try:
        sequence = _long(entry.get("sequence"), 0)
        previous_hash = _text(entry, "previousHash")
        entry_hash = _text(entry, "hash")
        computed = content_hash(entry)
    except (ValueError, TypeError, AttributeError):
        return "the disclosed entry cannot be read"
    if sequence != link.sequence or previous_hash != link.previous_hash or entry_hash != link.hash:
        return "the disclosed entry does not belong to its link"
    if computed != link.content_hash:
        return "the disclosed entry does not match its content hash"
    if (sequence < low or sequence > high
            or agent_id is not None and agent_id != _nullable(entry, "agentId")
            or principal_id is not None and not erased(entry) and principal_id != _nullable(entry, "principalId")):
        return "the disclosed entry is outside the scope of the package"
    return None


def _keys(pkg, key_list, pinned, problems) -> Optional[tuple]:
    listed = {}
    rotations = []
    revocations = []
    try:
        for node in list(pkg.get("keys") or []) + list(key_list or []):
            key_id = _text(node, "keyId")
            key = PublicKey.from_base64(_text(node, "publicKey"))
            if key.key_id != key_id:
                problems.append("the key %s in the package does not match its key ID" % key_id)
                return None
            listed[key_id] = key
            rotation = node.get("rotation")
            if isinstance(rotation, dict):
                rotations.append(KeyRotation(key_id, key.encoded, _text(rotation, "previousKeyId"),
                                             _text(node, "activatedAt"), _text(rotation, "keySignature"),
                                             _nullable(rotation, "previousKeySignature")))
            revocation = node.get("revocation")
            if isinstance(revocation, dict):
                revocations.append(KeyRevocation(key_id, _text(revocation, "compromisedAt"),
                                                 _text(revocation, "revokedAt"), _text(revocation, "reason"),
                                                 _text(revocation, "revokerKeyId"), _text(revocation, "signature")))
        if pinned is None:
            return listed, revoked_keys(listed, revocations)
        return trusted_and_revoked(PublicKey.from_base64(pinned), listed.values(), rotations, revocations)
    except (ValueError, TypeError, AttributeError) as e:
        problems.append("the public keys cannot be read: %s" % e)
        return None


def _key(keys, checkpoint, pinned, problems):
    key = keys.get(checkpoint.key_id)
    if key is None:
        problems.append(
            "checkpoint %d is signed with key %s, which the pinned key does not reach through signed key rotations"
            % (checkpoint.sequence, checkpoint.key_id) if pinned else "the package has no key %s" % checkpoint.key_id)
    return key


def _pinned_key_id(pinned) -> Optional[str]:
    if pinned is None:
        return None
    try:
        return PublicKey.from_base64(pinned).key_id
    except ValueError:
        return None


def _report(valid, key_id, pinned_key_id, anchor, checkpoint, created_at, profiles, first, checked, disclosed, scope,
            log, revoked, problems) -> PackageReport:
    compliance = [{"id": i, "digest": d} for i, d in sorted(profiles or ())]
    return PackageReport(valid, key_id, pinned_key_id, anchor, checkpoint, created_at, compliance, first, checked,
                         disclosed,
                         _nullable(scope, "agentId"), _nullable(scope, "principalId"), log.origin, log.size,
                         log.proven, log.receipted, list(log.witnesses), list(revoked), list(problems))


def _object(node) -> dict:
    if not isinstance(node, dict):
        raise ValueError("an object is missing")
    return node


def _text(node, field_name) -> str:
    value = node.get(field_name) if isinstance(node, dict) else None
    if not isinstance(value, str):
        raise ValueError(field_name + " is missing")
    return value


def _nullable(node, field_name) -> Optional[str]:
    value = node.get(field_name) if isinstance(node, dict) else None
    return value if isinstance(value, str) else None


def _long(value, default) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return default
    return int(value)


def _java_instant(text: str) -> str:
    head, _, fraction = text.rstrip("Z").partition(".")
    fraction = fraction.rstrip("0")
    if fraction:
        fraction = fraction.ljust(3 * ((len(fraction) + 2) // 3), "0")
    return head + ("." + fraction if fraction else "") + "Z"


def _show(value) -> str:
    return "null" if value is None else str(value)


def _camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part.capitalize() for part in rest)
