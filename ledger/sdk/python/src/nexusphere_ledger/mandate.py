import base64
import hashlib
import json
import os
import threading
import time as _time
import uuid
import zlib
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional

from .keys import PrivateKey, PublicKey
from .timestamps import epoch_text

ALGORITHM = "EdDSA"
TYPE = "dc+sd-jwt"
VCT = "urn:nexusphere:vct:agent-mandate:1"
SELECTIVE = ("principal", "grant", "termsHash")
HASH_ALGORITHM = "sha-256"
STATUS_LIST_TYPE = "statuslist+jwt"
KEYS_PATH = "/public/v1/keys"

MALFORMED = "MALFORMED"
WRONG_TYPE = "WRONG_TYPE"
UNTRUSTED_ISSUER = "UNTRUSTED_ISSUER"
UNKNOWN_KEY = "UNKNOWN_KEY"
BAD_SIGNATURE = "BAD_SIGNATURE"
NOT_YET_VALID = "NOT_YET_VALID"
EXPIRED = "EXPIRED"
WRONG_AUDIENCE = "WRONG_AUDIENCE"
NOT_COVERED = "NOT_COVERED"
REVOKED = "REVOKED"
STATUS_UNAVAILABLE = "STATUS_UNAVAILABLE"


def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64url_decode(text: str) -> bytes:
    if not isinstance(text, str) or "=" in text.rstrip("=") or any(c in text for c in "+/ \n"):
        raise ValueError("Not base64url")
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _json(data: bytes):
    try:
        return json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise ValueError("The token is not valid JSON")


def _compact(value) -> bytes:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


@dataclass(frozen=True)
class Jws:
    header: dict
    payload: dict
    signing_input: bytes
    signature: str

    @classmethod
    def parse(cls, token: str) -> "Jws":
        if token is None:
            raise ValueError("The token is missing")
        parts = token.strip().split(".")
        if len(parts) != 3:
            raise ValueError("The token is not a compact JWS")
        try:
            header = _json(b64url_decode(parts[0]))
            payload = _json(b64url_decode(parts[1]))
        except ValueError:
            raise ValueError("The token is not valid JSON")
        if not isinstance(header, dict) or not isinstance(payload, dict):
            raise ValueError("The token header and payload must be JSON objects")
        return cls(header, payload, (parts[0] + "." + parts[1]).encode("ascii"), parts[2])

    @staticmethod
    def sign(header: dict, payload: dict, key: PrivateKey) -> str:
        signing_input = b64url_encode(_compact(header)) + "." + b64url_encode(_compact(payload))
        return signing_input + "." + b64url_encode(key.sign(signing_input.encode("ascii")))

    @staticmethod
    def sign_typed(token_type: str, key_id: str, payload: dict, key: PrivateKey) -> str:
        return Jws.sign({"alg": ALGORITHM, "typ": token_type, "kid": key_id}, payload, key)

    def verify(self, key: PublicKey) -> bool:
        try:
            raw = b64url_decode(self.signature)
        except ValueError:
            return False
        return key.verify(self.signing_input, raw)


@dataclass(frozen=True)
class Disclosure:
    encoded: str
    name: str
    value: object

    @classmethod
    def of(cls, name: str, value) -> "Disclosure":
        return cls.parse(b64url_encode(_compact([b64url_encode(os.urandom(16)), name, value])))

    @classmethod
    def parse(cls, encoded: str) -> "Disclosure":
        try:
            array = _json(b64url_decode(encoded))
        except ValueError:
            raise ValueError("A disclosure is not base64url JSON")
        if (not isinstance(array, list) or len(array) != 3 or not isinstance(array[0], str)
                or not isinstance(array[1], str) or array[1] in ("_sd", "...")):
            raise ValueError("A disclosure is not a [salt, name, value] array")
        return cls(encoded, array[1], array[2])

    @property
    def digest(self) -> str:
        return b64url_encode(hashlib.sha256(self.encoded.encode("ascii")).digest())


@dataclass(frozen=True)
class SdJwt:
    jwt: Jws
    issuer_jwt: str
    disclosures: List[Disclosure]

    @classmethod
    def parse(cls, token: str) -> "SdJwt":
        if token is None:
            raise ValueError("The token is missing")
        trimmed = token.strip()
        if not trimmed.endswith("~"):
            raise ValueError("The token is not an SD-JWT without key binding")
        parts = trimmed.split("~")
        disclosures = []
        seen = set()
        for part in parts[1:-1]:
            if part in seen:
                raise ValueError("A disclosure is repeated")
            seen.add(part)
            disclosures.append(Disclosure.parse(part))
        return cls(Jws.parse(parts[0]), parts[0], disclosures)

    @staticmethod
    def issue(header: dict, payload: dict, disclosures: List[Disclosure], key: PrivateKey) -> str:
        return Jws.sign(header, payload, key) + "~" + "".join(d.encoded + "~" for d in disclosures)

    def claims(self) -> dict:
        payload = self.jwt.payload
        if "_sd_alg" in payload and payload.get("_sd_alg") != HASH_ALGORITHM:
            raise ValueError("The token hashes its disclosures with an unsupported algorithm")
        by_digest = {d.digest: d for d in self.disclosures}
        claims = json.loads(json.dumps(payload))
        claims.pop("_sd_alg", None)
        used = set()
        _reveal(claims, by_digest, used)
        if len(used) != len(self.disclosures):
            raise ValueError("A disclosure is not referenced by the token")
        return claims

    def present(self, names) -> str:
        names = set(names)
        return self.issuer_jwt + "~" + "".join(d.encoded + "~" for d in self.disclosures if d.name in names)


def _reveal(node, by_digest, used):
    if isinstance(node, dict):
        digests = node.pop("_sd", None)
        for name in list(node):
            _reveal(node[name], by_digest, used)
        if digests is None:
            return
        if not isinstance(digests, list):
            raise ValueError("The token has a malformed _sd claim")
        for digest in digests:
            disclosure = by_digest.get(digest) if isinstance(digest, str) else None
            if disclosure is None:
                continue
            if digest in used or disclosure.name in node:
                raise ValueError("The disclosed claim %s is repeated" % disclosure.name)
            used.add(digest)
            node[disclosure.name] = disclosure.value
    elif isinstance(node, list):
        for item in node:
            _reveal(item, by_digest, used)


def _is_number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


@dataclass(frozen=True)
class MandateClaims:
    issuer: str
    mandate_id: str
    agent_id: str
    principal_id: Optional[str]
    audience: Optional[str]
    actions: List[str]
    targets: List[str]
    max_uses: Optional[int]
    grant_id: Optional[str]
    terms_hash: Optional[str]
    issued_at: int
    not_before: int
    expires_at: int
    status_list_url: str
    status_index: int

    @classmethod
    def from_payload(cls, p: dict) -> "MandateClaims":
        if p.get("vct") != VCT:
            raise ValueError("The token is not a %s credential" % VCT)
        m = p.get("mandate") if isinstance(p.get("mandate"), dict) else {}
        status = p.get("status") if isinstance(p.get("status"), dict) else {}
        s = status.get("status_list") if isinstance(status.get("status_list"), dict) else {}
        grant = m.get("grant")
        return cls(
            _required(p, "iss"),
            str(uuid.UUID(_required(p, "jti"))),
            _required(p, "sub"),
            _optional(m, "principal"),
            _optional(p, "aud"),
            _strings(m.get("actions")),
            _strings(m.get("targets")),
            int(m["maxUses"]) if _is_number(m.get("maxUses")) else None,
            str(uuid.UUID(grant)) if isinstance(grant, str) else None,
            _optional(m, "termsHash"),
            _number(p, "iat"),
            _number(p, "nbf"),
            _number(p, "exp"),
            _required(s, "uri"),
            _number(s, "idx"))

    def covers(self, action: str, target: Optional[str]) -> bool:
        return (any(_matches(p, action) for p in self.actions)
                and any(_matches(p, target) for p in self.targets))

    def covers_action(self, action: str) -> bool:
        return any(_matches(p, action) for p in self.actions)

    def to_payload(self) -> dict:
        payload = {"iss": self.issuer, "vct": VCT, "sub": self.agent_id}
        if self.audience is not None:
            payload["aud"] = self.audience
        payload.update({"jti": self.mandate_id, "iat": self.issued_at, "nbf": self.not_before,
                        "exp": self.expires_at})
        mandate = {}
        if self.principal_id is not None:
            mandate["principal"] = self.principal_id
        mandate["actions"] = list(self.actions)
        mandate["targets"] = list(self.targets)
        if self.max_uses is not None:
            mandate["maxUses"] = self.max_uses
        if self.grant_id is not None:
            mandate["grant"] = self.grant_id
        if self.terms_hash is not None:
            mandate["termsHash"] = self.terms_hash
        payload["mandate"] = mandate
        payload["status"] = {"status_list": {"idx": self.status_index, "uri": self.status_list_url}}
        return payload


def _matches(pattern: str, value: Optional[str]) -> bool:
    if value is None:
        return pattern == "*"
    return value.startswith(pattern[:-1]) if pattern.endswith("*") else pattern == value


def _required(node: dict, name: str) -> str:
    if not isinstance(node.get(name), str):
        raise ValueError("The mandate has no " + name)
    return node[name]


def _optional(node: dict, name: str) -> Optional[str]:
    return node[name] if isinstance(node.get(name), str) else None


def _number(node: dict, name: str) -> int:
    if not _is_number(node.get(name)):
        raise ValueError("The mandate has no " + name)
    return int(node[name])


def _strings(array) -> List[str]:
    if not isinstance(array, list) or not array:
        raise ValueError("The mandate has no actions or targets")
    return [item if isinstance(item, str) else json.dumps(item) for item in array]


@dataclass(frozen=True)
class StatusList:
    issuer: Optional[str]
    uri: Optional[str]
    issued_at: int
    expires_at: int
    size: int
    revoked: bytes

    def is_revoked(self, index: int) -> bool:
        if index < 0 or index >= self.size:
            return True
        byte = index // 8
        return byte < len(self.revoked) and self.revoked[byte] & (1 << (index % 8)) != 0

    @classmethod
    def from_payload(cls, payload: dict) -> "StatusList":
        lst = payload.get("status_list") if isinstance(payload.get("status_list"), dict) else {}
        if lst.get("bits") != 1:
            raise ValueError("Only one bit per status is supported")
        size = int(lst["size"]) if _is_number(lst.get("size")) else 0
        try:
            data = zlib.decompress(b64url_decode(lst.get("lst") or ""))
        except (zlib.error, ValueError):
            raise ValueError("The status list cannot be decompressed")
        return cls(_optional(payload, "iss"), _optional(payload, "sub"),
                   int(payload.get("iat") or 0), int(payload.get("exp") or 0), size, data)

    @classmethod
    def of(cls, issuer: str, uri: str, issued_at: int, expires_at: int, size: int, revoked) -> "StatusList":
        bits = bytearray((size + 7) // 8)
        for index in revoked:
            if 0 <= index < size:
                bits[index // 8] |= 1 << (index % 8)
        return cls(issuer, uri, issued_at, expires_at, size, bytes(bits))

    def sign(self, key_id: str, key: PrivateKey) -> str:
        return Jws.sign_typed(STATUS_LIST_TYPE, key_id, {
            "iss": self.issuer, "sub": self.uri, "iat": self.issued_at, "exp": self.expires_at,
            "status_list": {"bits": 1, "size": self.size, "lst": b64url_encode(zlib.compress(self.revoked))},
        }, key)


def jwk_public_key(jwk: dict) -> PublicKey:
    if jwk.get("kty") != "OKP" or jwk.get("crv") != "Ed25519":
        raise ValueError("Only Ed25519 OKP keys are supported")
    raw = b64url_decode(jwk.get("x") or "")
    if len(raw) != 32:
        raise ValueError("An Ed25519 key has 32 bytes")
    return PublicKey.from_raw(raw)


def jwk_of(key: PublicKey) -> dict:
    return {"kty": "OKP", "crv": "Ed25519", "kid": key.key_id, "use": "sig", "alg": ALGORITHM,
            "x": b64url_encode(key.raw)}


Fetcher = Callable[[str, str], str]


def http_fetcher(timeout: float = 10.0) -> Fetcher:
    import urllib.request

    def fetch(url: str, accept: str) -> str:
        request = urllib.request.Request(url, headers={"Accept": accept})
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status != 200:
                raise IOError("%s answered %d" % (url, response.status))
            return response.read().decode("utf-8")

    return fetch


class StaticKeys:

    def __init__(self, keys: Dict[str, Dict[str, PublicKey]]):
        self._keys = keys

    @classmethod
    def fixed(cls, issuer: str, key: PublicKey) -> "StaticKeys":
        return cls({issuer.rstrip("/"): {key.key_id: key}})

    def resolve(self, issuer: str, key_id: str) -> Optional[PublicKey]:
        return self._keys.get(issuer, {}).get(key_id)


class JwksKeys:
    MIN_REFRESH = 30

    def __init__(self, fetch: Fetcher, ttl: float = 300, clock: Callable[[], float] = _time.time):
        self._fetch = fetch
        self._ttl = ttl
        self._clock = clock
        self._cache = {}
        self._lock = threading.Lock()

    def resolve(self, issuer: str, key_id: str) -> Optional[PublicKey]:
        now = self._clock()
        with self._lock:
            cached = self._cache.get(issuer)
        stale = cached is None or now > cached[1] + self._ttl
        unknown = cached is not None and key_id not in cached[0] and now > cached[1] + self.MIN_REFRESH
        if stale or unknown:
            cached = (self._load(issuer), now)
            with self._lock:
                self._cache[issuer] = cached
        return cached[0].get(key_id)

    def _load(self, issuer: str) -> Dict[str, PublicKey]:
        jwks = json.loads(self._fetch(issuer + KEYS_PATH, "application/jwk-set+json"))
        keys = {}
        for jwk in jwks.get("keys") or []:
            kid = jwk.get("kid") if isinstance(jwk, dict) else None
            if not isinstance(kid, str) or jwk.get("kty") != "OKP":
                continue
            try:
                keys[kid] = jwk_public_key(jwk)
            except ValueError:
                pass
        return keys


class HttpStatusLists:

    def __init__(self, fetch: Fetcher, keys, max_age: float = 300, clock: Callable[[], float] = _time.time):
        self._fetch = fetch
        self._keys = keys
        self._max_age = max_age
        self._clock = clock
        self._cache = {}
        self._lock = threading.Lock()

    def resolve(self, issuer: str, uri: str) -> StatusList:
        now = self._clock()
        with self._lock:
            cached = self._cache.get(uri)
        if cached is not None and now < cached[1]:
            return cached[0]
        status_list = self._verify(issuer, uri, self._fetch(uri, "application/statuslist+jwt"), now)
        with self._lock:
            self._cache[uri] = (status_list, min(status_list.expires_at, now + self._max_age))
        return status_list

    def _verify(self, issuer: str, uri: str, token: str, now: float) -> StatusList:
        parsed = Jws.parse(token)
        if parsed.header.get("typ") != STATUS_LIST_TYPE or parsed.header.get("alg") != ALGORITHM:
            raise ValueError("The status list at %s is not an EdDSA statuslist+jwt" % uri)
        key = self._keys.resolve(issuer, parsed.header.get("kid") or "")
        if key is None:
            raise ValueError("The status list at %s is signed with an unknown key" % uri)
        if not parsed.verify(key):
            raise ValueError("The status list at %s has a bad signature" % uri)
        status_list = StatusList.from_payload(parsed.payload)
        if status_list.issuer != issuer or status_list.uri != uri:
            raise ValueError("The status list at %s belongs to another issuer or address" % uri)
        if not now < status_list.expires_at:
            raise ValueError("The status list at %s has expired" % uri)
        return status_list


@dataclass(frozen=True)
class Problem:
    code: str
    message: str


@dataclass(frozen=True)
class MandateCheck:
    claims: Optional[MandateClaims]
    problems: List[Problem] = field(default_factory=list)

    @property
    def valid(self) -> bool:
        return self.claims is not None and not self.problems

    def has(self, code: str) -> bool:
        return any(problem.code == code for problem in self.problems)


class MandateVerifier:

    def __init__(self, issuers, keys=None, status_lists=None, skip_status: bool = False,
                 audience: Optional[str] = None, clock: Callable[[], float] = _time.time, clock_skew: float = 60,
                 cache_ttl: float = 300, timeout: float = 10, fetch: Optional[Fetcher] = None):
        if isinstance(issuers, str):
            issuers = [issuers]
        self._issuers = {issuer[:-1] if issuer.endswith("/") else issuer for issuer in issuers}
        if not self._issuers:
            raise ValueError("At least one trusted issuer is required")
        fetch = fetch or http_fetcher(timeout)
        self._clock = clock
        self._skew = clock_skew
        self._audience = audience
        self._keys = keys or JwksKeys(fetch, cache_ttl, clock)
        self._status_lists = None if skip_status else status_lists or HttpStatusLists(fetch, self._keys, cache_ttl,
                                                                                         clock)

    def verify(self, token: str) -> MandateCheck:
        return self._check(token, None, None, False)

    def verify_action(self, token: str, action: str, target: Optional[str]) -> MandateCheck:
        return self._check(token, action, target, True)

    def _check(self, token, action, target, coverage) -> MandateCheck:
        try:
            sd_jwt = SdJwt.parse(token)
            parsed = sd_jwt.jwt
            claims = MandateClaims.from_payload(sd_jwt.claims())
        except ValueError as e:
            return MandateCheck(None, [Problem(MALFORMED, str(e))])
        if parsed.header.get("typ") != TYPE or parsed.header.get("alg") != ALGORITHM:
            return MandateCheck(claims, [Problem(WRONG_TYPE, "The token is not an EdDSA " + TYPE)])
        if claims.issuer not in self._issuers:
            return MandateCheck(claims, [Problem(UNTRUSTED_ISSUER, "The issuer %s is not trusted" % claims.issuer)])
        kid = parsed.header.get("kid") if isinstance(parsed.header.get("kid"), str) else ""
        try:
            key = self._keys.resolve(claims.issuer, kid)
        except Exception as e:
            return MandateCheck(claims, [Problem(UNKNOWN_KEY, "The keys of %s cannot be read: %s"
                                                 % (claims.issuer, e))])
        if key is None:
            return MandateCheck(claims, [Problem(UNKNOWN_KEY, "The issuer has no key " + kid)])
        if not parsed.verify(key):
            return MandateCheck(claims, [Problem(BAD_SIGNATURE, "The signature does not match the issuer's key")])
        problems = []
        now = self._clock()
        if now + self._skew < claims.not_before:
            problems.append(Problem(NOT_YET_VALID, "The mandate is valid from " + epoch_text(claims.not_before)))
        if not now - self._skew < claims.expires_at:
            problems.append(Problem(EXPIRED, "The mandate expired at " + epoch_text(claims.expires_at)))
        if self._audience is not None and self._audience != claims.audience:
            problems.append(Problem(WRONG_AUDIENCE, "The mandate is for %s, not %s"
                                    % (claims.audience, self._audience)))
        if coverage and not claims.covers(action, target):
            problems.append(Problem(NOT_COVERED, "The mandate does not cover %s on %s" % (action, target)))
        if self._status_lists is not None:
            problems.extend(self._status(claims))
        return MandateCheck(claims, problems)

    def _status(self, claims: MandateClaims) -> List[Problem]:
        if not claims.status_list_url.startswith(claims.issuer + "/"):
            return [Problem(STATUS_UNAVAILABLE, "The status list %s is not served by the issuer"
                            % claims.status_list_url)]
        try:
            status_list = self._status_lists.resolve(claims.issuer, claims.status_list_url)
        except Exception as e:
            return [Problem(STATUS_UNAVAILABLE, "The revocation status cannot be checked: %s" % e)]
        if status_list.is_revoked(claims.status_index):
            return [Problem(REVOKED, "The mandate has been revoked")]
        return []
