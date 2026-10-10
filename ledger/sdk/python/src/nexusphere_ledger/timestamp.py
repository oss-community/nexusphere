import base64
import hashlib
import hmac
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import List, Optional, Sequence

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, ed25519, padding, rsa
from cryptography.hazmat.primitives.serialization import Encoding

SIGNED_DATA = "1.2.840.113549.1.7.2"
TST_INFO = "1.2.840.113549.1.9.16.1.4"
TIME_STAMPING = "1.3.6.1.5.5.7.3.8"
_CONTENT_TYPE = "1.2.840.113549.1.9.3"
_MESSAGE_DIGEST = "1.2.840.113549.1.9.4"
_SIGNING_CERTIFICATE = "1.2.840.113549.1.9.16.2.12"
_SIGNING_CERTIFICATE_V2 = "1.2.840.113549.1.9.16.2.47"
_DIGESTS = {
    "1.3.14.3.2.26": "sha1",
    "2.16.840.1.101.3.4.2.1": "sha256",
    "2.16.840.1.101.3.4.2.2": "sha384",
    "2.16.840.1.101.3.4.2.3": "sha512",
}
_HASHES = {"sha1": hashes.SHA1, "sha256": hashes.SHA256, "sha384": hashes.SHA384, "sha512": hashes.SHA512}
_SIGNATURES = {
    "1.2.840.10045.4.3.2": ("ecdsa", "sha256"),
    "1.2.840.10045.4.3.3": ("ecdsa", "sha384"),
    "1.2.840.10045.4.3.4": ("ecdsa", "sha512"),
    "1.2.840.113549.1.1.11": ("rsa", "sha256"),
    "1.2.840.113549.1.1.12": ("rsa", "sha384"),
    "1.2.840.113549.1.1.13": ("rsa", "sha512"),
    "1.3.101.112": ("ed25519", None),
}
SEQUENCE = 0x30
SET = 0x31
INTEGER = 0x02
OCTET_STRING = 0x04
OID = 0x06
GENERALIZED_TIME = 0x18


@dataclass(frozen=True)
class _Node:
    tag: int
    data: bytes
    start: int
    content_start: int
    end: int

    @property
    def encoded(self) -> bytes:
        return self.data[self.start:self.end]

    @property
    def content(self) -> bytes:
        return self.data[self.content_start:self.end]

    def children(self) -> List["_Node"]:
        children = []
        offset = self.content_start
        while offset < self.end:
            child = _read(self.data, offset, self.end)
            children.append(child)
            offset = child.end
        return children

    def child(self, index: int) -> "_Node":
        children = self.children()
        if index >= len(children):
            raise ValueError("DER element %d is missing" % index)
        return children[index]

    def expect(self, tag: int) -> "_Node":
        if self.tag != tag:
            raise ValueError("expected DER tag %02x but found %02x" % (tag, self.tag))
        return self

    def oid(self) -> str:
        self.expect(OID)
        parts = []
        value = 0
        for byte in self.content:
            value = (value << 7) | (byte & 0x7F)
            if not byte & 0x80:
                if not parts:
                    first = min(value // 40, 2)
                    parts += [first, value - first * 40]
                else:
                    parts.append(value)
                value = 0
        return ".".join(str(part) for part in parts)

    def integer(self) -> int:
        return int.from_bytes(self.expect(INTEGER).content, "big", signed=True)

    def generalized_time(self) -> datetime:
        text = self.expect(GENERALIZED_TIME).content.decode("ascii")
        if not text.endswith("Z") or len(text) < 15:
            raise ValueError("GeneralizedTime must be UTC")
        moment = datetime.strptime(text[:14], "%Y%m%d%H%M%S").replace(tzinfo=timezone.utc)
        fraction = text[14:-1]
        if fraction.startswith(".") and len(fraction) > 1:
            moment += timedelta(microseconds=int((fraction[1:] + "000000")[:6]))
        return moment


def _read(data: bytes, offset: int = 0, limit: Optional[int] = None) -> _Node:
    limit = len(data) if limit is None else limit
    if offset + 2 > limit:
        raise ValueError("truncated DER element")
    tag = data[offset]
    if tag & 0x1F == 0x1F:
        raise ValueError("high DER tag numbers are not supported")
    first = data[offset + 1]
    position = offset + 2
    if first < 0x80:
        length = first
    else:
        count = first & 0x7F
        if count == 0 or count > 4 or position + count > limit:
            raise ValueError("invalid DER length")
        length = int.from_bytes(data[position:position + count], "big")
        position += count
    if position + length > limit:
        raise ValueError("truncated DER element")
    return _Node(tag, data, offset, position, position + length)


def _der(data: bytes) -> _Node:
    node = _read(data)
    if node.end != len(data):
        raise ValueError("trailing bytes after DER element")
    return node


def _encode(tag: int, *parts: bytes) -> bytes:
    content = b"".join(parts)
    length = len(content)
    if length < 0x80:
        header = bytes([length])
    else:
        size = (length.bit_length() + 7) // 8
        header = bytes([0x80 | size]) + length.to_bytes(size, "big")
    return bytes([tag]) + header + content


def _digest(oid: str) -> str:
    name = _DIGESTS.get(oid)
    if name is None:
        raise ValueError("unsupported digest algorithm " + oid)
    return name


def _instant(moment: datetime) -> str:
    text = moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    fraction = ("%06d" % moment.microsecond).rstrip("0")
    if fraction:
        text += "." + fraction.ljust(3 * ((len(fraction) + 2) // 3), "0")
    return text + "Z"


def read_certificates(pem_or_der) -> List[x509.Certificate]:
    data = pem_or_der.encode("ascii") if isinstance(pem_or_der, str) else bytes(pem_or_der)
    try:
        if b"-----BEGIN" in data:
            return list(x509.load_pem_x509_certificates(data))
        return [x509.load_der_x509_certificate(data)]
    except ValueError as e:
        raise ValueError("the certificates cannot be read") from e


def timestamp_request(sha256: bytes, nonce: int) -> bytes:
    algorithm = _encode(SEQUENCE, _encode(OID, bytes.fromhex("608648016503040201")), _encode(0x05))
    imprint = _encode(SEQUENCE, algorithm, _encode(OCTET_STRING, sha256))
    nonce_bytes = nonce.to_bytes((nonce.bit_length() + 8) // 8, "big", signed=True)
    return _encode(SEQUENCE, _encode(INTEGER, b"\x01"), imprint, _encode(INTEGER, nonce_bytes),
                   _encode(0x01, b"\xff"))


@dataclass(frozen=True)
class TimestampVerification:
    valid: bool
    gen_time: Optional[str] = None
    authority: Optional[str] = None
    pinned: bool = False
    problem: Optional[str] = None


@dataclass(frozen=True)
class TimestampToken:
    encoded: bytes
    hash_algorithm: str
    message_imprint: bytes
    gen_time: datetime
    serial_number: int
    nonce: Optional[int]
    policy: str
    certificates: tuple
    signer_identifier: bytes
    signer_by_key_id: bool
    digest_algorithm: str
    signed_attributes: bytes
    message_digest: Optional[bytes]
    certificate_hash: Optional[bytes]
    certificate_hash_algorithm: Optional[str]
    signature_algorithm: str
    signature: bytes
    content: bytes

    @classmethod
    def from_response(cls, response: bytes) -> "TimestampToken":
        parts = _der(response).expect(SEQUENCE).children()
        status = parts[0].expect(SEQUENCE).child(0).integer()
        if status not in (0, 1):
            raise ValueError("the timestamp authority refused the request with status %d" % status)
        if len(parts) < 2:
            raise ValueError("the timestamp response has no token")
        return cls.parse(parts[1].encoded)

    @classmethod
    def parse(cls, encoded) -> "TimestampToken":
        if isinstance(encoded, str):
            encoded = base64.b64decode(encoded, validate=True)
        content_info = _der(encoded).expect(SEQUENCE)
        if content_info.child(0).oid() != SIGNED_DATA:
            raise ValueError("a timestamp token must be CMS signed data")
        fields = content_info.child(1).expect(0xA0).child(0).expect(SEQUENCE).children()
        encapsulated = fields[2].expect(SEQUENCE)
        if encapsulated.child(0).oid() != TST_INFO:
            raise ValueError("the signed content is not a TSTInfo")
        content = encapsulated.child(1).expect(0xA0).child(0).expect(OCTET_STRING).content
        certificates = []
        signer_infos = None
        for item in fields[3:]:
            if item.tag == 0xA0:
                certificates += [x509.load_der_x509_certificate(c.encoded) for c in item.children()
                                 if c.tag == SEQUENCE]
            elif item.tag == SET:
                signer_infos = item
        if signer_infos is None or len(signer_infos.children()) != 1:
            raise ValueError("a timestamp token must have exactly one signer")
        signer = signer_infos.child(0).expect(SEQUENCE).children()
        sid = signer[1]
        by_key_id = sid.tag == 0x80
        digest_algorithm = _digest(signer[2].child(0).oid())
        attributes = signer[3].expect(0xA0)
        signed_attributes = bytes([SET]) + attributes.encoded[1:]
        message_digest = None
        certificate_hash = None
        certificate_hash_algorithm = None
        tst_content_type = False
        for attribute in attributes.children():
            kind = attribute.child(0).oid()
            value = attribute.child(1).expect(SET).child(0)
            if kind == _CONTENT_TYPE:
                tst_content_type = value.oid() == TST_INFO
            elif kind == _MESSAGE_DIGEST:
                message_digest = value.expect(OCTET_STRING).content
            elif kind == _SIGNING_CERTIFICATE:
                certificate_hash = value.child(0).child(0).child(0).expect(OCTET_STRING).content
                certificate_hash_algorithm = "sha1"
            elif kind == _SIGNING_CERTIFICATE_V2:
                cert_id = value.child(0).child(0)
                first = cert_id.child(0)
                if first.tag == SEQUENCE:
                    certificate_hash_algorithm = _digest(first.child(0).oid())
                    certificate_hash = cert_id.child(1).expect(OCTET_STRING).content
                else:
                    certificate_hash_algorithm = "sha256"
                    certificate_hash = first.expect(OCTET_STRING).content
        if not tst_content_type:
            raise ValueError("the signed attributes do not name a TSTInfo")
        info = _der(content).expect(SEQUENCE).children()
        imprint = info[2].expect(SEQUENCE)
        nonce = None
        for item in info[5:]:
            if item.tag == INTEGER:
                nonce = item.integer()
        return cls(bytes(encoded), _digest(imprint.child(0).child(0).oid()),
                   imprint.child(1).expect(OCTET_STRING).content, info[4].generalized_time(), info[3].integer(),
                   nonce, info[1].oid(), tuple(certificates), sid.content if by_key_id else sid.encoded, by_key_id,
                   digest_algorithm, signed_attributes, message_digest, certificate_hash,
                   certificate_hash_algorithm, signer[4].child(0).oid(), signer[5].expect(OCTET_STRING).content,
                   content)

    def base64(self) -> str:
        return base64.b64encode(self.encoded).decode("ascii")

    def imprint_hex(self) -> str:
        return self.message_imprint.hex()

    def verify(self, expected_sha256: bytes, trusted: Sequence[x509.Certificate] = ()) -> TimestampVerification:
        try:
            if self.hash_algorithm != "sha256" or not hmac.compare_digest(self.message_imprint, expected_sha256):
                return TimestampVerification(False, problem="the timestamp is for different data")
            if self.message_digest is None or not hmac.compare_digest(
                    self.message_digest, hashlib.new(self.digest_algorithm, self.content).digest()):
                return TimestampVerification(False, problem="the timestamp signature does not cover its content")
            signer = self._signer(trusted)
            if signer is None:
                return TimestampVerification(False,
                                             problem="the timestamp does not carry the certificate of its signer")
            encoded = signer.public_bytes(Encoding.DER)
            if self.certificate_hash is not None and not hmac.compare_digest(
                    self.certificate_hash, hashlib.new(self.certificate_hash_algorithm, encoded).digest()):
                return TimestampVerification(False, problem="the timestamp names a different signing certificate")
            if TIME_STAMPING not in _usages(signer):
                return TimestampVerification(False, problem="the signing certificate is not for timestamping")
            if not _verify_signature(signer.public_key(), self.signature_algorithm, self.digest_algorithm,
                                     self.signed_attributes, self.signature):
                return TimestampVerification(False, problem="the timestamp signature is not valid")
            if not _valid_at(signer, self.gen_time):
                return TimestampVerification(False, problem="the timestamp cannot be verified: the signing "
                                                            "certificate is not valid at the timestamp")
            pinned = bool(trusted)
            if pinned and not self._chains_to(signer, list(trusted)):
                return TimestampVerification(False, problem="the timestamp authority is not trusted")
            return TimestampVerification(True, _instant(self.gen_time), signer.subject.rfc4514_string(), pinned)
        except (ValueError, TypeError, KeyError) as e:
            return TimestampVerification(False, problem="the timestamp cannot be verified: %s" % e)

    def _signer(self, trusted) -> Optional[x509.Certificate]:
        for certificate in list(self.certificates) + list(trusted or ()):
            if self.signer_by_key_id:
                try:
                    key_id = certificate.extensions.get_extension_for_class(x509.SubjectKeyIdentifier).value.digest
                except x509.ExtensionNotFound:
                    continue
                if key_id == self.signer_identifier:
                    return certificate
            else:
                sid = _der(self.signer_identifier)
                if (certificate.issuer.public_bytes() == sid.child(0).encoded
                        and certificate.serial_number == sid.child(1).integer()):
                    return certificate
        return None

    def _chains_to(self, certificate, trusted) -> bool:
        current = certificate
        for _ in range(6):
            if current is None:
                return False
            for anchor in trusted:
                if anchor == current or self._issued(anchor, current):
                    return True
            current = next((c for c in self.certificates if c != current and self._issued(c, current)), None)
        return False

    def _issued(self, issuer, subject) -> bool:
        if issuer.subject != subject.issuer:
            return False
        try:
            subject.verify_directly_issued_by(issuer)
        except (ValueError, TypeError, InvalidSignature):
            return False
        return _valid_at(issuer, self.gen_time)


def _usages(certificate) -> List[str]:
    try:
        usage = certificate.extensions.get_extension_for_class(x509.ExtendedKeyUsage).value
    except x509.ExtensionNotFound:
        return []
    return [oid.dotted_string for oid in usage]


def _valid_at(certificate, moment: datetime) -> bool:
    return certificate.not_valid_before_utc <= moment <= certificate.not_valid_after_utc


def _verify_signature(key, algorithm: str, digest: str, data: bytes, signature: bytes) -> bool:
    kind, hash_name = _SIGNATURES.get(algorithm, (None, None))
    if kind is None:
        if algorithm == "1.2.840.113549.1.1.1":
            kind, hash_name = "rsa", digest
        elif algorithm == "1.2.840.10045.2.1":
            kind, hash_name = "ecdsa", digest
        else:
            raise ValueError("unsupported signature algorithm " + algorithm)
    try:
        if kind == "ecdsa" and isinstance(key, ec.EllipticCurvePublicKey):
            key.verify(signature, data, ec.ECDSA(_HASHES[hash_name]()))
        elif kind == "rsa" and isinstance(key, rsa.RSAPublicKey):
            key.verify(signature, data, padding.PKCS1v15(), _HASHES[hash_name]())
        elif kind == "ed25519" and isinstance(key, ed25519.Ed25519PublicKey):
            key.verify(signature, data)
        else:
            return False
    except InvalidSignature:
        return False
    return True

