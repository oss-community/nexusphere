import time as _time
import uuid
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional

from .evidence import is_sha256
from .index import IndexCheck
from .keys import PrivateKey, PublicKey
from .mandate import (ALGORITHM, BAD_SIGNATURE, EXPIRED, HASH_ALGORITHM, KEY_BINDING_INVALID, KEY_BINDING_MISSING,
                      KEY_NOT_BOUND, MALFORMED, NOT_YET_VALID, TYPE, UNKNOWN_KEY, UNTRUSTED_ISSUER, WRONG_AUDIENCE,
                      WRONG_TYPE, Disclosure, Fetcher, JwksKeys, Problem, SdJwt, confirmation_jwk, http_fetcher,
                      jwk_public_key, key_binding_problem)
from .timestamps import epoch_text, parse_instant

VCT = "urn:nexusphere:vct:agent-track-record:1"
SELECTIVE = ("outcomes", "principals", "actions")


@dataclass(frozen=True)
class TrackRecordClaims:
    issuer: str
    record_id: str
    agent_id: str
    audience: Optional[str]
    issued_at: int
    expires_at: int
    checkpoint_sequence: int
    head_hash: str
    index_root: str
    total: int
    first_sequence: Optional[int] = None
    last_sequence: Optional[int] = None
    first_at: Optional[str] = None
    last_at: Optional[str] = None
    outcomes: Optional[Dict[str, int]] = None
    principals: Optional[int] = None
    actions: Optional[Dict[str, int]] = None
    holder_key: Optional[PublicKey] = None

    def __post_init__(self):
        if not is_sha256(self.head_hash) or not is_sha256(self.index_root):
            raise ValueError("The track record names no valid head hash or index root")
        if self.outcomes is not None:
            object.__setattr__(self, "outcomes", dict(sorted(self.outcomes.items())))
        if self.actions is not None:
            object.__setattr__(self, "actions", dict(sorted(self.actions.items())))

    @property
    def bound(self) -> bool:
        return self.holder_key is not None

    @classmethod
    def from_payload(cls, p: dict) -> "TrackRecordClaims":
        if p.get("vct") != VCT:
            raise ValueError("The token is not a %s credential" % VCT)
        r = p.get("record") if isinstance(p.get("record"), dict) else {}
        c = r.get("checkpoint") if isinstance(r.get("checkpoint"), dict) else {}
        active = _is_number(r.get("firstSequence"))
        cnf = p.get("cnf")
        return cls(
            _required(p, "iss"),
            str(uuid.UUID(_required(p, "jti"))),
            _required(p, "sub"),
            p["aud"] if isinstance(p.get("aud"), str) else None,
            _number(p, "iat"),
            _number(p, "exp"),
            _number(c, "sequence"),
            _required(c, "headHash"),
            _required(c, "indexRoot"),
            _number(r, "total"),
            _number(r, "firstSequence") if active else None,
            _number(r, "lastSequence") if active else None,
            _instant(r, "firstAt") if active else None,
            _instant(r, "lastAt") if active else None,
            _counts(r.get("outcomes")),
            int(r["principals"]) if _is_number(r.get("principals")) else None,
            _counts(r.get("actions")),
            jwk_public_key(cnf.get("jwk") if isinstance(cnf.get("jwk"), dict) else {})
            if isinstance(cnf, dict) else None)

    def to_payload(self) -> dict:
        payload = {"iss": self.issuer, "vct": VCT, "sub": self.agent_id}
        if self.audience is not None:
            payload["aud"] = self.audience
        payload.update({"jti": self.record_id, "iat": self.issued_at, "nbf": self.issued_at,
                        "exp": self.expires_at})
        record = {"checkpoint": {"sequence": self.checkpoint_sequence, "headHash": self.head_hash,
                                 "indexRoot": self.index_root},
                  "total": self.total}
        if self.first_sequence is not None:
            record.update({"firstSequence": self.first_sequence, "lastSequence": self.last_sequence,
                           "firstAt": self.first_at, "lastAt": self.last_at})
        if self.outcomes is not None:
            record["outcomes"] = dict(self.outcomes)
        if self.principals is not None:
            record["principals"] = self.principals
        if self.actions is not None:
            record["actions"] = dict(self.actions)
        payload["record"] = record
        if self.holder_key is not None:
            payload["cnf"] = {"jwk": confirmation_jwk(self.holder_key)}
        return payload


def issue_track_record(claims: TrackRecordClaims, key_id: str, key: PrivateKey) -> str:
    payload = claims.to_payload()
    record = payload["record"]
    disclosures = [Disclosure.of(name, record.pop(name)) for name in SELECTIVE if name in record]
    record["_sd"] = sorted(d.digest for d in disclosures)
    payload["_sd_alg"] = HASH_ALGORITHM
    return SdJwt.issue({"alg": ALGORITHM, "typ": TYPE, "kid": key_id}, payload, disclosures, key)


def track_record_claims(token: str) -> TrackRecordClaims:
    return TrackRecordClaims.from_payload(SdJwt.parse(token).claims())


def match_track_record(claims: TrackRecordClaims, proof: dict, check: IndexCheck) -> List[str]:
    problems = []
    if check.term.get("type") != "agent" or check.term.get("agentId") != claims.agent_id:
        problems.append("The index proof is not about the agent " + claims.agent_id)
    index = proof.get("index") if isinstance(proof, dict) and isinstance(proof.get("index"), dict) else {}
    if (check.sequence is None or check.sequence != claims.checkpoint_sequence
            or claims.head_hash != check.head_hash or claims.index_root != index.get("root")):
        problems.append("The index proof is for another checkpoint or index root than the track record")
    count = check.count if check.present else 0
    if (count != claims.total or (check.first_sequence if check.present else None) != claims.first_sequence
            or (check.last_sequence if check.present else None) != claims.last_sequence):
        problems.append("The track record claims %d entries but the index proves %s" % (claims.total, count))
    return problems


def _is_number(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _required(node: dict, name: str) -> str:
    if not isinstance(node.get(name), str):
        raise ValueError("The track record has no " + name)
    return node[name]


def _number(node: dict, name: str) -> int:
    if not _is_number(node.get(name)):
        raise ValueError("The track record has no " + name)
    return node[name]


def _instant(node: dict, name: str) -> str:
    parse_instant(_required(node, name))
    return node[name]


def _counts(node) -> Optional[Dict[str, int]]:
    if node is None:
        return None
    if not isinstance(node, dict):
        raise ValueError("The track record has malformed counts")
    counts = {}
    for name, value in node.items():
        if not _is_number(value) or value < 0:
            raise ValueError("The track record has a malformed count for " + name)
        counts[name] = value
    return counts


@dataclass(frozen=True)
class TrackRecordCheck:
    claims: Optional[TrackRecordClaims]
    problems: List[Problem] = field(default_factory=list)

    @property
    def valid(self) -> bool:
        return self.claims is not None and not self.problems

    def has(self, code: str) -> bool:
        return any(problem.code == code for problem in self.problems)


class TrackRecordVerifier:

    def __init__(self, issuers, keys=None, audience: Optional[str] = None,
                 clock: Callable[[], float] = _time.time, clock_skew: float = 60, cache_ttl: float = 300,
                 timeout: float = 10, fetch: Optional[Fetcher] = None, require_key_binding: bool = False,
                 key_binding_max_age: float = 300):
        if isinstance(issuers, str):
            issuers = [issuers]
        self._issuers = {issuer[:-1] if issuer.endswith("/") else issuer for issuer in issuers}
        if not self._issuers:
            raise ValueError("At least one trusted issuer is required")
        self._clock = clock
        self._skew = clock_skew
        self._audience = audience
        self._require_key_binding = require_key_binding
        self._key_binding_max_age = key_binding_max_age
        self._keys = keys or JwksKeys(fetch or http_fetcher(timeout), cache_ttl, clock)

    def verify(self, token: str) -> TrackRecordCheck:
        return self.verify_bound(token, None)

    def verify_bound(self, token: str, nonce: Optional[str]) -> TrackRecordCheck:
        try:
            sd_jwt = SdJwt.parse(token)
            parsed = sd_jwt.jwt
            claims = TrackRecordClaims.from_payload(sd_jwt.claims())
        except ValueError as e:
            return TrackRecordCheck(None, [Problem(MALFORMED, str(e))])
        if parsed.header.get("typ") != TYPE or parsed.header.get("alg") != ALGORITHM:
            return TrackRecordCheck(claims, [Problem(WRONG_TYPE, "The token is not an EdDSA " + TYPE)])
        if claims.issuer not in self._issuers:
            return TrackRecordCheck(claims, [Problem(UNTRUSTED_ISSUER, "The issuer %s is not trusted"
                                                     % claims.issuer)])
        kid = parsed.header.get("kid") if isinstance(parsed.header.get("kid"), str) else ""
        try:
            key = self._keys.resolve(claims.issuer, kid)
        except Exception as e:
            return TrackRecordCheck(claims, [Problem(UNKNOWN_KEY, "The keys of %s cannot be read: %s"
                                                     % (claims.issuer, e))])
        if key is None:
            return TrackRecordCheck(claims, [Problem(UNKNOWN_KEY, "The issuer has no key " + kid)])
        if not parsed.verify(key):
            return TrackRecordCheck(claims, [Problem(BAD_SIGNATURE, "The signature does not match the issuer's key")])
        problems = []
        now = self._clock()
        if now + self._skew < claims.issued_at:
            problems.append(Problem(NOT_YET_VALID, "The track record is valid from " + epoch_text(claims.issued_at)))
        if not now - self._skew < claims.expires_at:
            problems.append(Problem(EXPIRED, "The track record expired at " + epoch_text(claims.expires_at)))
        if self._audience is not None and claims.audience is not None and self._audience != claims.audience:
            problems.append(Problem(WRONG_AUDIENCE, "The track record is for %s, not %s"
                                    % (claims.audience, self._audience)))
        binding = self._key_binding(sd_jwt, claims, nonce, now)
        if binding is not None:
            problems.append(binding)
        return TrackRecordCheck(claims, problems)

    def _key_binding(self, sd_jwt: SdJwt, claims: TrackRecordClaims, nonce, now) -> Optional[Problem]:
        if not claims.bound:
            if sd_jwt.key_binding is not None:
                return Problem(KEY_BINDING_INVALID,
                               "The track record names no agent key, so it cannot carry a key binding")
            if self._require_key_binding:
                return Problem(KEY_NOT_BOUND, "The track record is not bound to a key of the agent")
            return None
        if sd_jwt.key_binding is None:
            return Problem(KEY_BINDING_MISSING,
                           "The track record is bound to a key of the agent and needs a key binding")
        problem = key_binding_problem(sd_jwt, claims.holder_key,
                                      self._audience if self._audience is not None else claims.audience, nonce, now,
                                      self._skew, self._key_binding_max_age)
        return None if problem is None else Problem(KEY_BINDING_INVALID, problem)
