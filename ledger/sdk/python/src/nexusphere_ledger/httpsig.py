import base64
import hashlib
import os
import time as _time
from dataclasses import dataclass
from typing import Callable, Dict, List, Mapping, Optional
from urllib.parse import urlsplit

from .keys import PrivateKey, PublicKey

LABEL = "nexusphere"
TAG = "nexusphere-ledger"
ALGORITHM = "ed25519"
VALIDITY = 300


class InvalidHttpSignature(ValueError):
    pass


@dataclass(frozen=True)
class VerifiedRequest:
    key_id: str
    created: int
    nonce: Optional[str]


def content_digest(body: bytes) -> str:
    return "sha-256=:" + base64.b64encode(hashlib.sha256(body).digest()).decode("ascii") + ":"


def authority(url: str) -> str:
    parts = urlsplit(url)
    host = (parts.hostname or "").lower()
    port = parts.port
    if port is None or (parts.scheme == "http" and port == 80) or (parts.scheme == "https" and port == 443):
        return host
    return "%s:%d" % (host, port)


def _components(body: Optional[bytes]) -> List[str]:
    if not body:
        return ["@method", "@authority", "@path"]
    return ["@method", "@authority", "@path", "content-digest"]


def _value(component: str, method: str, url: str, header: Callable[[str], Optional[str]]) -> str:
    if component == "@method":
        return method.upper()
    if component == "@authority":
        return authority(url)
    if component == "@path":
        return urlsplit(url).path or "/"
    if component == "@query":
        return "?" + urlsplit(url).query
    if component.startswith("@"):
        raise InvalidHttpSignature("The component %s is not supported" % component)
    value = header(component)
    if value is None:
        raise InvalidHttpSignature("The request has no %s header" % component)
    return value.strip()


def signature_base(method: str, url: str, components: List[str], header: Callable[[str], Optional[str]],
                   params: str) -> str:
    lines = ['"%s": %s' % (c, _value(c, method, url, header)) for c in components]
    return "\n".join(lines + ['"@signature-params": ' + params])


def sign_request(method: str, url: str, body: Optional[bytes], key_id: str, key: PrivateKey,
                 created: Optional[int] = None, nonce: Optional[str] = None) -> Dict[str, str]:
    components = _components(body)
    created = int(_time.time()) if created is None else int(created)
    nonce = nonce or base64.urlsafe_b64encode(os.urandom(16)).rstrip(b"=").decode("ascii")
    headers = {}
    if "content-digest" in components:
        headers["Content-Digest"] = content_digest(body)
    params = "(%s);created=%d;expires=%d;nonce=\"%s\";keyid=\"%s\";alg=\"%s\";tag=\"%s\"" % (
        " ".join('"%s"' % c for c in components), created, created + VALIDITY, nonce, key_id, ALGORITHM, TAG)
    digest = headers.get("Content-Digest")
    base = signature_base(method, url, components, lambda n: digest if n.lower() == "content-digest" else None,
                          params)
    headers["Signature-Input"] = LABEL + "=" + params
    headers["Signature"] = LABEL + "=:" + base64.b64encode(key.sign(base.encode("utf-8"))).decode("ascii") + ":"
    return headers


def verify_request(method: str, url: str, headers: Mapping[str, str], body: Optional[bytes],
                   keys: Callable[[str], Optional[PublicKey]], now: Optional[float] = None,
                   clock_skew: float = 60) -> VerifiedRequest:
    lower = {name.lower(): value for name, value in headers.items()}
    header = lower.get
    now = _time.time() if now is None else now
    raw_input = _member(header("signature-input"), "The request has no Signature-Input " + LABEL)
    signature = _member(header("signature"), "The request has no Signature " + LABEL)
    components, params = _parse(raw_input)
    required = _components(body)
    if not all(c in components for c in required):
        raise InvalidHttpSignature("The signature must cover " + ", ".join(required))
    if params.get("alg") is not None and _text(params.get("alg")) != ALGORITHM:
        raise InvalidHttpSignature("The signature algorithm must be " + ALGORITHM)
    if _text(params.get("tag")) != TAG:
        raise InvalidHttpSignature("The signature tag must be " + TAG)
    created = _number(params.get("created"))
    expires = _number(params.get("expires"))
    if created is None or expires is None:
        raise InvalidHttpSignature("The signature needs created and expires")
    if created > now + clock_skew or expires < now - clock_skew or expires - created > VALIDITY:
        raise InvalidHttpSignature("The signature was made at %d and is not valid now" % created)
    key_id = _text(params.get("keyid"))
    if key_id is None:
        raise InvalidHttpSignature("The signature has no keyid")
    if "content-digest" in components:
        digest = header("content-digest")
        if digest is None or digest.strip() != content_digest(body or b""):
            raise InvalidHttpSignature("The Content-Digest does not match the body")
    key = keys(key_id)
    if key is None:
        raise InvalidHttpSignature("The key %s is unknown" % key_id)
    if len(signature) < 2 or not signature.startswith(":") or not signature.endswith(":"):
        raise InvalidHttpSignature("The signature is not a byte sequence")
    try:
        raw = base64.b64decode(signature[1:-1], validate=True)
    except ValueError:
        raise InvalidHttpSignature("The signature is not base64")
    base = signature_base(method, url, components, header, raw_input)
    if not key.verify(base.encode("utf-8"), raw):
        raise InvalidHttpSignature("The signature does not match the request")
    return VerifiedRequest(key_id, created, _text(params.get("nonce")))


def _member(dictionary: Optional[str], missing: str) -> str:
    if dictionary is None:
        raise InvalidHttpSignature(missing)
    for member in _split(dictionary):
        name, sep, value = member.partition("=")
        if sep and name.strip() == LABEL:
            return value.strip()
    raise InvalidHttpSignature(missing)


def _split(dictionary: str) -> List[str]:
    members, depth, quoted, start = [], 0, False, 0
    for i, c in enumerate(dictionary):
        if c == '"':
            quoted = not quoted
        elif not quoted and c == "(":
            depth += 1
        elif not quoted and c == ")":
            depth -= 1
        elif not quoted and depth == 0 and c == ",":
            members.append(dictionary[start:i])
            start = i + 1
    members.append(dictionary[start:])
    return members


def _parse(text: str):
    if not text.startswith("(") or ")" not in text:
        raise InvalidHttpSignature("The signature input is not an inner list")
    close = text.index(")")
    components = []
    for item in text[1:close].split():
        if len(item) < 2 or not item.startswith('"') or not item.endswith('"'):
            raise InvalidHttpSignature("A covered component is not a string")
        components.append(item[1:-1])
    params = {}
    for param in text[close + 1:].split(";"):
        if not param.strip():
            continue
        name, sep, value = param.partition("=")
        if not sep:
            raise InvalidHttpSignature("A signature parameter has no value")
        params[name.strip()] = value.strip()
    return components, params


def _text(value: Optional[str]) -> Optional[str]:
    if value is None or len(value) < 2 or not value.startswith('"') or not value.endswith('"'):
        return None
    return value[1:-1]


def _number(value: Optional[str]) -> Optional[int]:
    try:
        return int(value) if value is not None else None
    except ValueError:
        return None
