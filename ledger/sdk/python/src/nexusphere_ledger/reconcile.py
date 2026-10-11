import uuid
from dataclasses import dataclass, field
from typing import List, Optional

from .evidence import is_sha256
from .keys import PrivateKey
from .mandate import ALGORITHM, Jws
from .timestamps import epoch_text

STATEMENT_TYPE = "nexusphere-a2a-statement+jwt"
OUTBOUND = "OUTBOUND"
INBOUND = "INBOUND"
MAX_EXCHANGES = 10_000
NOT_RECEIVED = "NOT_RECEIVED"
NOT_SENT = "NOT_SENT"
MISMATCH = "MISMATCH"
UNDELIVERED = frozenset((401, 502, 503, 504))


@dataclass(frozen=True)
class Exchange:
    direction: str
    request_id: str
    requested_at: int
    method: str
    mandate_id: str
    request_hash: str
    response_hash: Optional[str] = None
    status: Optional[int] = None
    outcome: Optional[str] = None
    receipt_hash: Optional[str] = None

    def __post_init__(self):
        if self.direction not in (OUTBOUND, INBOUND):
            raise ValueError("The exchange direction must be OUTBOUND or INBOUND")
        if (not is_sha256(self.request_hash) or (self.response_hash is not None and not is_sha256(self.response_hash))
                or (self.receipt_hash is not None and not is_sha256(self.receipt_hash))):
            raise ValueError("The exchange %s has a malformed hash" % self.request_id)
        object.__setattr__(self, "request_id", str(uuid.UUID(self.request_id)))
        object.__setattr__(self, "mandate_id", str(uuid.UUID(self.mandate_id)))

    @classmethod
    def from_payload(cls, n: dict) -> "Exchange":
        if not isinstance(n, dict):
            raise ValueError("The token has a malformed exchange")
        status = n.get("status")
        return cls(_text(n, "direction"), _text(n, "requestId"), _number(n, "requestedAt"), _text(n, "method"),
                   _text(n, "mandateId"), _text(n, "requestHash"), _optional(n, "responseHash"),
                   status if isinstance(status, int) and not isinstance(status, bool) else None,
                   _optional(n, "outcome"), _optional(n, "receiptHash"))

    def to_payload(self) -> dict:
        return {"direction": self.direction, "requestId": self.request_id, "requestedAt": self.requested_at,
                "method": self.method, "mandateId": self.mandate_id, "requestHash": self.request_hash,
                "responseHash": self.response_hash, "status": self.status, "outcome": self.outcome,
                "receiptHash": self.receipt_hash}


@dataclass(frozen=True)
class ExchangeStatement:
    issuer: str
    peer: str
    start: int
    end: int
    issued_at: int
    exchanges: List[Exchange] = field(default_factory=list)

    def __post_init__(self):
        if len(self.exchanges) > MAX_EXCHANGES:
            raise ValueError("A statement holds at most %d exchanges" % MAX_EXCHANGES)
        if not self.start < self.end:
            raise ValueError("The statement window must end after it starts")
        object.__setattr__(self, "exchanges", sorted(self.exchanges, key=lambda e: (e.requested_at,
                                                                                    _uuid_order(e.request_id))))

    @classmethod
    def from_payload(cls, p: dict) -> "ExchangeStatement":
        if not isinstance(p.get("exchanges"), list):
            raise ValueError("The token has no exchanges")
        items = [Exchange.from_payload(item) for item in p["exchanges"]]
        return cls(_text(p, "iss"), _text(p, "peer"), _number(p, "from"), _number(p, "to"), _number(p, "iat"),
                   items)

    def to_payload(self) -> dict:
        return {"iss": self.issuer, "peer": self.peer, "iat": self.issued_at, "from": self.start, "to": self.end,
                "exchanges": [e.to_payload() for e in self.exchanges]}

    def sign(self, key_id: str, key: PrivateKey) -> str:
        return Jws.sign_typed(STATEMENT_TYPE, key_id, self.to_payload(), key)


def parse_statement(token: str) -> ExchangeStatement:
    return ExchangeStatement.from_payload(Jws.parse(token).payload)


def verify_statement(token: str, keys, issuer: Optional[str] = None) -> ExchangeStatement:
    parsed = Jws.parse(token)
    if issuer is None:
        issuer = parsed.payload.get("iss") if isinstance(parsed.payload.get("iss"), str) else ""
    if parsed.header.get("typ") != STATEMENT_TYPE or parsed.header.get("alg") != ALGORITHM:
        raise ValueError("The token is not an EdDSA " + STATEMENT_TYPE)
    if parsed.payload.get("iss") != issuer:
        raise ValueError("The token was not issued by " + issuer)
    key = keys.resolve(issuer, parsed.header.get("kid") if isinstance(parsed.header.get("kid"), str) else "")
    if key is None:
        raise ValueError("The token is signed with an unknown key")
    if not parsed.verify(key):
        raise ValueError("The token signature does not match the issuer's key")
    return ExchangeStatement.from_payload(parsed.payload)


@dataclass(frozen=True)
class Discrepancy:
    request_id: str
    kind: str
    sender: str
    requested_at: int
    fields: List[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {"requestId": self.request_id, "kind": self.kind, "sender": self.sender,
                "requestedAt": epoch_text(self.requested_at), "fields": list(self.fields)}


@dataclass(frozen=True)
class Reconciliation:
    ours: str
    theirs: str
    start: int
    end: int
    matched: int
    undelivered: int
    discrepancies: List[Discrepancy] = field(default_factory=list)

    @property
    def reconciled(self) -> bool:
        return not self.discrepancies

    def to_dict(self) -> dict:
        return {"ours": self.ours, "theirs": self.theirs, "from": epoch_text(self.start), "to": epoch_text(self.end),
                "matched": self.matched, "undelivered": self.undelivered,
                "discrepancies": [d.to_dict() for d in self.discrepancies]}


def reconcile(ours: ExchangeStatement, theirs: ExchangeStatement) -> Reconciliation:
    if ours.peer != theirs.issuer or theirs.peer != ours.issuer:
        raise ValueError("The statements are not about each other: %s names %s and %s names %s"
                         % (ours.issuer, ours.peer, theirs.issuer, theirs.peer))
    if ours.start != theirs.start or ours.end != theirs.end:
        raise ValueError("The statements cover different windows")
    counts = [0, 0]
    discrepancies = []
    _compare(ours, theirs, counts, discrepancies)
    _compare(theirs, ours, counts, discrepancies)
    discrepancies.sort(key=lambda d: (d.requested_at, _uuid_order(d.request_id)))
    return Reconciliation(ours.issuer, theirs.issuer, ours.start, ours.end, counts[0], counts[1], discrepancies)


def _compare(sender: ExchangeStatement, receiver: ExchangeStatement, counts, discrepancies):
    received = {}
    for item in receiver.exchanges:
        if item.direction == INBOUND:
            received[item.request_id] = item
    seen = set()
    for sent in sender.exchanges:
        if sent.direction != OUTBOUND:
            continue
        seen.add(sent.request_id)
        inbound = received.get(sent.request_id)
        if inbound is None:
            if sent.receipt_hash is None and sent.status in UNDELIVERED:
                counts[1] += 1
            else:
                discrepancies.append(Discrepancy(sent.request_id, NOT_RECEIVED, sender.issuer, sent.requested_at))
            continue
        differing = _fields(sent, inbound)
        if not differing:
            counts[0] += 1
        else:
            discrepancies.append(Discrepancy(sent.request_id, MISMATCH, sender.issuer, sent.requested_at, differing))
    for item in received.values():
        if item.request_id not in seen:
            discrepancies.append(Discrepancy(item.request_id, NOT_SENT, sender.issuer, item.requested_at))


def _fields(sent: Exchange, inbound: Exchange) -> List[str]:
    differing = []
    if sent.requested_at != inbound.requested_at:
        differing.append("requestedAt")
    if sent.method != inbound.method:
        differing.append("method")
    if sent.mandate_id != inbound.mandate_id:
        differing.append("mandateId")
    if sent.request_hash != inbound.request_hash:
        differing.append("requestHash")
    if _both(sent.status, inbound.status) and sent.status != inbound.status:
        differing.append("status")
    if _both(sent.response_hash, inbound.response_hash) and sent.response_hash != inbound.response_hash:
        differing.append("responseHash")
    if _both(sent.receipt_hash, inbound.receipt_hash):
        if sent.receipt_hash != inbound.receipt_hash:
            differing.append("receiptHash")
        elif sent.outcome != inbound.outcome:
            differing.append("outcome")
    return differing


def _both(a, b) -> bool:
    return a is not None and b is not None


def _uuid_order(value: str):
    number = uuid.UUID(value).int
    return _signed(number >> 64), _signed(number & 0xFFFFFFFFFFFFFFFF)


def _signed(value: int) -> int:
    return value - (1 << 64) if value >= 1 << 63 else value


def _text(node: dict, name: str) -> str:
    if not isinstance(node.get(name), str):
        raise ValueError("The token has no " + name)
    return node[name]


def _optional(node: dict, name: str) -> Optional[str]:
    return node[name] if isinstance(node.get(name), str) else None


def _number(node: dict, name: str) -> int:
    value = node.get(name)
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError("The token has no " + name)
    return int(value)
