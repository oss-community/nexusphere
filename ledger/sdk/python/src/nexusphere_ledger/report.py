from dataclasses import asdict, dataclass
from typing import Dict, List, Optional

from .canonical import canonical_bytes, sha256_hex
from .package import trusted_package_keys

REPORT_FORMAT = "nexusphere-ledger/report/v1"
REPORT_STATUSES = ("SHOWN", "PARTLY_SHOWN", "NOT_SHOWN")
_SCOPE = ("agentId", "principalId", "fromSequence", "toSequence")


@dataclass(frozen=True)
class ReportCheck:
    valid: bool
    report: Optional[str]
    title: Optional[str]
    key_id: Optional[str]
    checkpoint_sequence: Optional[int]
    entries: Optional[int]
    statuses: Dict[str, int]
    problems: List[str]

    def to_dict(self) -> dict:
        return {_camel(key): value for key, value in asdict(self).items()}


def report_signed_bytes(report: dict) -> bytes:
    signed = _object(report)
    signed.pop("signature", None)
    return canonical_bytes(signed)


def entries_digest(hashes) -> str:
    return sha256_hex(canonical_bytes({"hashes": list(hashes)}))


def verify_report(report: dict, package: dict, public_key: Optional[str] = None, keys=None) -> ReportCheck:
    problems = []
    node = report if isinstance(report, dict) else {}
    pkg = package if isinstance(package, dict) else {}
    report_id = _string(node.get("report"))
    title = _string(node.get("title"))
    key_id = _string(node.get("keyId"))
    evidence = _node(node, "evidence")
    sequence = _integer(evidence.get("checkpoint"))
    entries = _integer(evidence.get("entries"))
    counted = {}
    for section in _items(node.get("sections")):
        section = section if isinstance(section, dict) else {}
        status = _string(section.get("status"), "")
        if status not in REPORT_STATUSES:
            problems.append("section %s has an unknown status %s" % (_string(section.get("id"), "?"), status))
        counted[status] = counted.get(status, 0) + 1
    statuses = dict(sorted(counted.items()))
    if _string(node.get("format")) != REPORT_FORMAT:
        problems.append("unknown report format %s" % _show(_string(node.get("format"))))
        return ReportCheck(False, report_id, title, key_id, sequence, entries, statuses, problems)
    try:
        signed = report_signed_bytes(node)
    except ValueError as e:
        problems.append("the report cannot be read: %s" % e)
        return ReportCheck(False, report_id, title, key_id, sequence, entries, statuses, problems)
    trusted = trusted_package_keys(pkg, keys, public_key, problems)
    key = None if key_id is None else trusted.get(key_id)
    if key is None:
        problems.append("the report is signed with key %s, which the package does not trust" % _show(key_id))
    elif _revoked(pkg, keys, key_id):
        problems.append("the report is signed with revoked key %s" % key_id)
    elif not key.verify_base64(signed, _string(node.get("signature"), "")):
        problems.append("the signature of the report is not valid")
    checkpoint = _node(pkg, "checkpoint")
    if (sequence is None or sequence != _integer(checkpoint.get("sequence"), -1)
            or _string(evidence.get("headHash")) != _string(checkpoint.get("headHash"))):
        problems.append("the report is for another checkpoint than the package")
    scope = _node(node, "scope")
    package_scope = _node(pkg, "scope")
    for name in _SCOPE:
        if _value(scope.get(name)) != _value(package_scope.get(name)):
            problems.append("the report has another %s than the package" % name)
    hashes = [_string(link.get("hash"), "") for link in _items(pkg.get("links"))
              if isinstance(link, dict) and isinstance(link.get("entry"), dict)]
    if (entries is None or entries != len(hashes)
            or entries_digest(hashes) != _string(evidence.get("entriesDigest"), "")):
        problems.append("the report covers other entries than the package discloses")
    return ReportCheck(not problems, report_id, title, key_id, sequence, entries, statuses, problems)


def _revoked(pkg, keys, key_id) -> bool:
    nodes = _items(pkg.get("keys")) + _items(keys)
    return any(isinstance(node, dict) and node.get("keyId") == key_id and isinstance(node.get("revocation"), dict)
               for node in nodes)


def _object(node) -> dict:
    if not isinstance(node, dict):
        raise ValueError("a report must be a JSON object")
    return {name: _canonical(value) for name, value in node.items()}


def _canonical(node):
    if node is None or isinstance(node, (str, bool)):
        return node
    if isinstance(node, dict):
        return _object(node)
    if isinstance(node, (list, tuple)):
        return [_canonical(item) for item in node]
    if isinstance(node, int) and -2 ** 63 <= node < 2 ** 63:
        return node
    raise ValueError("reports hold only strings, integers, booleans, arrays and objects")


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


def _value(value):
    if value is None:
        return None
    return value if _integer(value) is not None else _string(value, "")


def _show(value) -> str:
    return "null" if value is None else value


def _camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part.capitalize() for part in rest)
