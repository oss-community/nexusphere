import { concat, equal, fromBase64, fromUtf8, toHex } from "./bytes.js";

const SEQUENCE = 0x30;
const SET = 0x31;
const INTEGER = 0x02;
const BIT_STRING = 0x03;
const OCTET_STRING = 0x04;
const OID = 0x06;
const UTC_TIME = 0x17;
const GENERALIZED_TIME = 0x18;

export const SIGNED_DATA = "1.2.840.113549.1.7.2";
export const TST_INFO = "1.2.840.113549.1.9.16.1.4";
export const TIME_STAMPING = "1.3.6.1.5.5.7.3.8";
const CONTENT_TYPE = "1.2.840.113549.1.9.3";
const MESSAGE_DIGEST = "1.2.840.113549.1.9.4";
const SIGNING_CERTIFICATE = "1.2.840.113549.1.9.16.2.12";
const SIGNING_CERTIFICATE_V2 = "1.2.840.113549.1.9.16.2.47";
const EXTENDED_KEY_USAGE = "2.5.29.37";
const SUBJECT_KEY_IDENTIFIER = "2.5.29.14";
const EC_PUBLIC_KEY = "1.2.840.10045.2.1";
const RSA_ENCRYPTION = "1.2.840.113549.1.1.1";
const ED25519 = "1.3.101.112";

const DIGESTS: Record<string, string> = {
  "1.3.14.3.2.26": "SHA-1",
  "2.16.840.1.101.3.4.2.1": "SHA-256",
  "2.16.840.1.101.3.4.2.2": "SHA-384",
  "2.16.840.1.101.3.4.2.3": "SHA-512",
};

type Algorithm = { kind: "ECDSA" | "RSA" | "Ed25519"; hash: string | null };

const SIGNATURES: Record<string, Algorithm> = {
  "1.2.840.10045.4.3.2": { kind: "ECDSA", hash: "SHA-256" },
  "1.2.840.10045.4.3.3": { kind: "ECDSA", hash: "SHA-384" },
  "1.2.840.10045.4.3.4": { kind: "ECDSA", hash: "SHA-512" },
  "1.2.840.113549.1.1.11": { kind: "RSA", hash: "SHA-256" },
  "1.2.840.113549.1.1.12": { kind: "RSA", hash: "SHA-384" },
  "1.2.840.113549.1.1.13": { kind: "RSA", hash: "SHA-512" },
  [ED25519]: { kind: "Ed25519", hash: null },
};

const CURVES: Record<string, { name: string; size: number }> = {
  "1.2.840.10045.3.1.7": { name: "P-256", size: 32 },
  "1.3.132.0.34": { name: "P-384", size: 48 },
  "1.3.132.0.35": { name: "P-521", size: 66 },
};

const NAME_KEYWORDS: Record<string, string> = {
  "2.5.4.3": "CN",
  "2.5.4.6": "C",
  "2.5.4.7": "L",
  "2.5.4.8": "ST",
  "2.5.4.10": "O",
  "2.5.4.11": "OU",
  "2.5.4.9": "STREET",
  "0.9.2342.19200300.100.1.25": "DC",
  "0.9.2342.19200300.100.1.1": "UID",
};

const MAX_CHAIN_DEPTH = 6;

class Node {
  constructor(
    readonly tag: number,
    private readonly data: Uint8Array,
    readonly start: number,
    readonly contentStart: number,
    readonly end: number,
  ) {}

  encoded(): Uint8Array {
    return this.data.slice(this.start, this.end);
  }

  content(): Uint8Array {
    return this.data.slice(this.contentStart, this.end);
  }

  children(): Node[] {
    const children: Node[] = [];
    let offset = this.contentStart;
    while (offset < this.end) {
      const child = readAt(this.data, offset, this.end);
      children.push(child);
      offset = child.end;
    }
    return children;
  }

  child(index: number): Node {
    const children = this.children();
    if (index >= children.length) {
      throw new Error(`DER element ${index} is missing`);
    }
    return children[index];
  }

  expect(expected: number): Node {
    if (this.tag !== expected) {
      throw new Error(`expected DER tag ${hex2(expected)} but found ${hex2(this.tag)}`);
    }
    return this;
  }

  oid(): string {
    this.expect(OID);
    const bytes = this.content();
    const parts: string[] = [];
    let value = 0n;
    for (const byte of bytes) {
      value = (value << 7n) | BigInt(byte & 0x7f);
      if ((byte & 0x80) === 0) {
        if (parts.length === 0) {
          const first = value / 40n < 2n ? value / 40n : 2n;
          parts.push(first.toString(), (value - first * 40n).toString());
        } else {
          parts.push(value.toString());
        }
        value = 0n;
      }
    }
    return parts.join(".");
  }

  integer(): bigint {
    this.expect(INTEGER);
    const bytes = this.content();
    if (bytes.length === 0) {
      throw new Error("Zero length BigInteger");
    }
    let value = 0n;
    for (const byte of bytes) {
      value = (value << 8n) | BigInt(byte);
    }
    return bytes[0] & 0x80 ? value - (1n << BigInt(8 * bytes.length)) : value;
  }

  generalizedTime(): Instant {
    this.expect(GENERALIZED_TIME);
    const text = latin1(this.content());
    if (!text.endsWith("Z") || text.length < 15) {
      throw new Error("GeneralizedTime must be UTC");
    }
    const seconds = dateSeconds(text.substring(0, 14));
    const fraction = text.substring(14, text.length - 1);
    let nanos = 0;
    if (fraction.startsWith(".") && fraction.length > 1) {
      const digits = (fraction.substring(1) + "000000000").substring(0, 9);
      if (!/^\d+$/.test(digits)) {
        throw new Error(`For input string: "${digits}"`);
      }
      nanos = Number(digits);
    }
    return { seconds, nanos };
  }

  time(): number {
    if (this.tag === UTC_TIME) {
      const text = latin1(this.content());
      if (!/^\d{12}Z$/.test(text)) {
        throw new Error("Invalid UTCTime");
      }
      const year = Number(text.substring(0, 2));
      return dateSeconds((year >= 50 ? "19" : "20") + text.substring(0, 12)) * 1000;
    }
    const instant = this.generalizedTime();
    return instant.seconds * 1000 + Math.floor(instant.nanos / 1_000_000);
  }
}

interface Instant {
  seconds: number;
  nanos: number;
}

function hex2(value: number): string {
  return value.toString(16).padStart(2, "0");
}

function latin1(bytes: Uint8Array): string {
  let out = "";
  for (const byte of bytes) {
    out += String.fromCharCode(byte);
  }
  return out;
}

function dateSeconds(text: string): number {
  const match = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})$/.exec(text);
  if (!match) {
    throw new Error(`Text '${text}' could not be parsed`);
  }
  const [, year, month, day, hour, minute, second] = match.map(Number);
  if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) {
    throw new Error(`Text '${text}' could not be parsed`);
  }
  return Date.UTC(year, month - 1, day, hour, minute, second) / 1000;
}

function readAt(data: Uint8Array, offset: number, limit: number): Node {
  if (offset + 2 > limit) {
    throw new Error("truncated DER element");
  }
  const tag = data[offset];
  if ((tag & 0x1f) === 0x1f) {
    throw new Error("high DER tag numbers are not supported");
  }
  const first = data[offset + 1];
  let position = offset + 2;
  let length: number;
  if (first < 0x80) {
    length = first;
  } else {
    const count = first & 0x7f;
    if (count === 0 || count > 4 || position + count > limit) {
      throw new Error("invalid DER length");
    }
    length = 0;
    for (let i = 0; i < count; i++) {
      length = length * 256 + data[position++];
    }
  }
  if (position + length > limit) {
    throw new Error("truncated DER element");
  }
  return new Node(tag, data, offset, position, position + length);
}

function read(data: Uint8Array): Node {
  const node = readAt(data, 0, data.length);
  if (node.end !== data.length) {
    throw new Error("trailing bytes after DER element");
  }
  return node;
}

function digestName(oid: string): string {
  const name = DIGESTS[oid];
  if (!name) {
    throw new Error("unsupported digest algorithm " + oid);
  }
  return name;
}

async function digest(algorithm: string, data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest(algorithm, data as BufferSource));
}

function instantText(instant: Instant): string {
  const base = new Date(instant.seconds * 1000).toISOString().substring(0, 19);
  let fraction = "";
  if (instant.nanos > 0) {
    const digits = instant.nanos.toString().padStart(9, "0");
    fraction = "." + (instant.nanos % 1_000_000 === 0 ? digits.substring(0, 3)
      : instant.nanos % 1000 === 0 ? digits.substring(0, 6) : digits);
  }
  return base + fraction + "Z";
}

function dateText(millis: number): string {
  const date = new Date(millis);
  const days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const two = (n: number) => n.toString().padStart(2, "0");
  return `${days[date.getUTCDay()]} ${months[date.getUTCMonth()]} ${two(date.getUTCDate())} ${two(date.getUTCHours())}:${
    two(date.getUTCMinutes())}:${two(date.getUTCSeconds())} UTC ${date.getUTCFullYear()}`;
}

interface Attribute {
  type: string;
  tag: number;
  value: Uint8Array;
  encoded: Uint8Array;
}

function attributeText(attribute: Attribute): string | null {
  const bytes = attribute.value;
  switch (attribute.tag) {
    case 0x0c:
      return fromUtf8(bytes);
    case 0x13:
    case 0x14:
    case 0x16:
    case 0x1b:
      return latin1(bytes);
    case 0x1e: {
      let out = "";
      for (let i = 0; i + 1 < bytes.length; i += 2) {
        out += String.fromCharCode((bytes[i] << 8) | bytes[i + 1]);
      }
      return out;
    }
    default:
      return null;
  }
}

function rfc2253(attribute: Attribute): string {
  const keyword = NAME_KEYWORDS[attribute.type];
  const text = attributeText(attribute);
  if (!keyword || text === null) {
    return `${keyword ?? attribute.type}=#${toHex(attribute.encoded)}`;
  }
  let value = "";
  for (const c of text) {
    value += (",=+<>#;\"\\".includes(c) ? "\\" : "") + c;
  }
  const leading = value.length - value.trimStart().length;
  const trimmed = value.trimEnd();
  const trailing = value.length - trimmed.length;
  value = "\\ ".repeat(leading) + value.substring(leading, trimmed.length) + "\\ ".repeat(trailing);
  return `${keyword}=${value}`;
}

function canonical(attribute: Attribute): string {
  const keyword = (NAME_KEYWORDS[attribute.type] ?? attribute.type).toLowerCase();
  const text = attributeText(attribute);
  const value = text === null ? "#" + toHex(attribute.encoded)
    : text.normalize("NFKD").trim().replace(/\s+/g, " ").toLowerCase();
  return `${keyword}=${value}`;
}

export class DistinguishedName {
  private constructor(
    readonly encoded: Uint8Array,
    private readonly rdns: Attribute[][],
  ) {}

  static parse(encoded: Uint8Array): DistinguishedName {
    const node = read(encoded);
    const rdns = node.expect(SEQUENCE).children().map((rdn) =>
      rdn.expect(SET).children().map((ava) => {
        const value = ava.expect(SEQUENCE).child(1);
        return { type: ava.child(0).oid(), tag: value.tag, value: value.content(), encoded: value.encoded() };
      }));
    return new DistinguishedName(node.encoded(), rdns);
  }

  get name(): string {
    return [...this.rdns].reverse().map((rdn) => rdn.map(rfc2253).join("+")).join(",");
  }

  get canonicalName(): string {
    return [...this.rdns].reverse().map((rdn) => rdn.map(canonical).sort().join("+")).join(",");
  }

  equals(other: DistinguishedName): boolean {
    return equal(this.encoded, other.encoded) || this.canonicalName === other.canonicalName;
  }

  toString(): string {
    return this.name;
  }
}

export class X509Certificate {
  private constructor(
    readonly encoded: Uint8Array,
    readonly tbs: Uint8Array,
    readonly serialNumber: bigint,
    readonly issuer: DistinguishedName,
    readonly subject: DistinguishedName,
    readonly notBefore: number,
    readonly notAfter: number,
    readonly subjectPublicKeyInfo: Uint8Array,
    readonly publicKeyAlgorithm: string,
    readonly publicKeyParameters: string | null,
    readonly extendedKeyUsage: string[] | null,
    readonly subjectKeyIdentifier: Uint8Array | null,
    readonly signatureAlgorithm: string,
    readonly signature: Uint8Array,
  ) {}

  static parse(encoded: Uint8Array): X509Certificate {
    const certificate = read(encoded).expect(SEQUENCE);
    const parts = certificate.children();
    if (parts.length !== 3) {
      throw new Error("a certificate has three parts");
    }
    const tbs = parts[0].expect(SEQUENCE);
    let fields = tbs.children();
    if (fields[0]?.tag === 0xa0) {
      fields = fields.slice(1);
    }
    if (fields.length < 6) {
      throw new Error("the certificate is incomplete");
    }
    const validity = fields[3].expect(SEQUENCE);
    const spki = fields[5].expect(SEQUENCE);
    const keyAlgorithm = spki.child(0).expect(SEQUENCE);
    const parameters = keyAlgorithm.children()[1];
    let extendedKeyUsage: string[] | null = null;
    let subjectKeyIdentifier: Uint8Array | null = null;
    for (const field of fields.slice(6)) {
      if (field.tag !== 0xa3) {
        continue;
      }
      for (const extension of field.child(0).expect(SEQUENCE).children()) {
        const items = extension.expect(SEQUENCE).children();
        const id = items[0].oid();
        const value = read(items[items.length - 1].expect(OCTET_STRING).content());
        if (id === EXTENDED_KEY_USAGE) {
          extendedKeyUsage = value.expect(SEQUENCE).children().map((usage) => usage.oid());
        } else if (id === SUBJECT_KEY_IDENTIFIER) {
          subjectKeyIdentifier = value.expect(OCTET_STRING).content();
        }
      }
    }
    const signature = parts[2].expect(BIT_STRING).content();
    if (signature.length === 0 || signature[0] !== 0) {
      throw new Error("the certificate signature is not a whole number of bytes");
    }
    return new X509Certificate(
      certificate.encoded(),
      tbs.encoded(),
      fields[0].integer(),
      DistinguishedName.parse(fields[2].encoded()),
      DistinguishedName.parse(fields[4].encoded()),
      validity.child(0).time(),
      validity.child(1).time(),
      spki.encoded(),
      keyAlgorithm.child(0).oid(),
      parameters && parameters.tag === OID ? parameters.oid() : null,
      extendedKeyUsage,
      subjectKeyIdentifier,
      parts[1].expect(SEQUENCE).child(0).oid(),
      signature.slice(1),
    );
  }

  get subjectName(): string {
    return this.subject.name;
  }

  get issuerName(): string {
    return this.issuer.name;
  }

  checkValidity(millis: number) {
    if (this.notBefore > millis) {
      throw new Error("NotBefore: " + dateText(this.notBefore));
    }
    if (this.notAfter < millis) {
      throw new Error("NotAfter: " + dateText(this.notAfter));
    }
  }

  equals(other: X509Certificate): boolean {
    return equal(this.encoded, other.encoded);
  }

  async verifySignature(algorithm: Algorithm, data: Uint8Array, signature: Uint8Array): Promise<boolean> {
    let key: CryptoKey;
    let raw = signature;
    if (algorithm.kind === "ECDSA") {
      const curve = this.publicKeyAlgorithm === EC_PUBLIC_KEY ? CURVES[this.publicKeyParameters ?? ""] : undefined;
      if (!curve) {
        throw new Error("the key is not a supported elliptic curve key");
      }
      key = await crypto.subtle.importKey("spki", this.subjectPublicKeyInfo as BufferSource,
        { name: "ECDSA", namedCurve: curve.name }, false, ["verify"]);
      const converted = rawEcdsa(signature, curve.size);
      if (converted === null) {
        return false;
      }
      raw = converted;
      return crypto.subtle.verify({ name: "ECDSA", hash: algorithm.hash! }, key, raw as BufferSource,
        data as BufferSource);
    }
    if (algorithm.kind === "RSA") {
      if (this.publicKeyAlgorithm !== RSA_ENCRYPTION) {
        throw new Error("the key is not an RSA key");
      }
      key = await crypto.subtle.importKey("spki", this.subjectPublicKeyInfo as BufferSource,
        { name: "RSASSA-PKCS1-v1_5", hash: algorithm.hash! }, false, ["verify"]);
      return crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, raw as BufferSource, data as BufferSource);
    }
    if (this.publicKeyAlgorithm !== ED25519) {
      throw new Error("the key is not an Ed25519 key");
    }
    key = await crypto.subtle.importKey("spki", this.subjectPublicKeyInfo as BufferSource, { name: "Ed25519" }, false,
      ["verify"]);
    return raw.length === 64 && crypto.subtle.verify("Ed25519", key, raw as BufferSource, data as BufferSource);
  }

  async verifiedBy(issuer: X509Certificate): Promise<boolean> {
    const algorithm = SIGNATURES[this.signatureAlgorithm];
    if (!algorithm) {
      return false;
    }
    try {
      return await issuer.verifySignature(algorithm, this.tbs, this.signature);
    } catch {
      return false;
    }
  }
}

function rawEcdsa(signature: Uint8Array, size: number): Uint8Array | null {
  let parts: Node[];
  try {
    parts = read(signature).expect(SEQUENCE).children();
    if (parts.length !== 2) {
      throw new Error();
    }
    parts.forEach((part) => part.expect(INTEGER));
  } catch {
    throw new Error("Invalid encoding for signature");
  }
  const out: Uint8Array[] = [];
  for (const part of parts) {
    let bytes = part.content();
    if (bytes.length === 0 || bytes[0] & 0x80) {
      return null;
    }
    let start = 0;
    while (start < bytes.length - 1 && bytes[start] === 0) {
      start++;
    }
    bytes = bytes.subarray(start);
    if (bytes.length > size) {
      return null;
    }
    const padded = new Uint8Array(size);
    padded.set(bytes, size - bytes.length);
    out.push(padded);
  }
  return concat(...out);
}

export function parseCertificate(der: Uint8Array): X509Certificate {
  return X509Certificate.parse(der);
}

export function readCertificates(input: string | Uint8Array): X509Certificate[] {
  try {
    if (input instanceof Uint8Array && input.length > 0 && input[0] === SEQUENCE) {
      const certificates: X509Certificate[] = [];
      let offset = 0;
      while (offset < input.length) {
        const node = readAt(input, offset, input.length);
        certificates.push(X509Certificate.parse(input.slice(offset, node.end)));
        offset = node.end;
      }
      return certificates;
    }
    const text = typeof input === "string" ? input : latin1(input);
    const blocks = [...text.matchAll(/-----BEGIN CERTIFICATE-----([\s\S]*?)-----END CERTIFICATE-----/g)];
    if (blocks.length === 0 && text.trim() !== "") {
      throw new Error("no certificate found");
    }
    return blocks.map((block) => X509Certificate.parse(fromBase64(block[1].replace(/\s+/g, ""))));
  } catch (e) {
    throw new Error("the certificates cannot be read: " + (e as Error).message);
  }
}

export interface TimestampVerification {
  valid: boolean;
  genTime: string | null;
  authority: string | null;
  pinned: boolean;
  problem: string | null;
}

function invalid(problem: string): TimestampVerification {
  return { valid: false, genTime: null, authority: null, pinned: false, problem };
}

export class TimestampToken {
  private constructor(
    readonly encoded: Uint8Array,
    readonly hashAlgorithm: string,
    readonly messageImprint: Uint8Array,
    private readonly genInstant: Instant,
    readonly serialNumber: bigint,
    readonly nonce: bigint | null,
    readonly policy: string,
    readonly certificates: X509Certificate[],
    readonly signerIdentifier: Uint8Array,
    readonly signerByKeyId: boolean,
    readonly digestAlgorithm: string,
    readonly signedAttributes: Uint8Array,
    readonly messageDigestAttribute: Uint8Array | null,
    readonly signingCertificateHash: Uint8Array | null,
    readonly signingCertificateHashAlgorithm: string | null,
    readonly signatureAlgorithm: string,
    readonly signature: Uint8Array,
    readonly content: Uint8Array,
  ) {}

  static fromResponse(response: Uint8Array): TimestampToken {
    const parts = read(response).expect(SEQUENCE).children();
    const status = Number(parts[0].expect(SEQUENCE).child(0).integer());
    if (status !== 0 && status !== 1) {
      throw new Error("the timestamp authority refused the request with status " + status);
    }
    if (parts.length < 2) {
      throw new Error("the timestamp response has no token");
    }
    return TimestampToken.parse(parts[1].encoded());
  }

  static parse(input: Uint8Array | string): TimestampToken {
    const encoded = typeof input === "string" ? fromBase64(input) : input;
    const contentInfo = read(encoded).expect(SEQUENCE);
    if (contentInfo.child(0).oid() !== SIGNED_DATA) {
      throw new Error("a timestamp token must be CMS signed data");
    }
    const signedData = contentInfo.child(1).expect(0xa0).child(0).expect(SEQUENCE);
    const fields = signedData.children();
    const encapsulated = field(fields, 2).expect(SEQUENCE);
    if (encapsulated.child(0).oid() !== TST_INFO) {
      throw new Error("the signed content is not a TSTInfo");
    }
    const content = encapsulated.child(1).expect(0xa0).child(0).expect(OCTET_STRING).content();
    const certificates: X509Certificate[] = [];
    let signerInfos: Node | null = null;
    for (const item of fields.slice(3)) {
      if (item.tag === 0xa0) {
        for (const child of item.children()) {
          if (child.tag === SEQUENCE) {
            certificates.push(...readCertificates(child.encoded()));
          }
        }
      } else if (item.tag === SET) {
        signerInfos = item;
      }
    }
    if (signerInfos === null || signerInfos.children().length !== 1) {
      throw new Error("a timestamp token must have exactly one signer");
    }
    const signer = signerInfos.child(0).expect(SEQUENCE).children();
    const sid = field(signer, 1);
    const byKeyId = sid.tag === 0x80;
    const digestAlgorithm = digestName(field(signer, 2).child(0).oid());
    const attributes = field(signer, 3).expect(0xa0);
    const signedAttributes = attributes.encoded();
    signedAttributes[0] = SET;
    let messageDigest: Uint8Array | null = null;
    let certificateHash: Uint8Array | null = null;
    let certificateHashAlgorithm: string | null = null;
    let tstContentType = false;
    for (const attribute of attributes.children()) {
      const type = attribute.child(0).oid();
      const value = attribute.child(1).expect(SET).child(0);
      if (type === CONTENT_TYPE) {
        tstContentType = value.oid() === TST_INFO;
      } else if (type === MESSAGE_DIGEST) {
        messageDigest = value.expect(OCTET_STRING).content();
      } else if (type === SIGNING_CERTIFICATE) {
        certificateHash = value.child(0).child(0).child(0).expect(OCTET_STRING).content();
        certificateHashAlgorithm = "SHA-1";
      } else if (type === SIGNING_CERTIFICATE_V2) {
        const certId = value.child(0).child(0);
        const first = certId.child(0);
        if (first.tag === SEQUENCE) {
          certificateHashAlgorithm = digestName(first.child(0).oid());
          certificateHash = certId.child(1).expect(OCTET_STRING).content();
        } else {
          certificateHashAlgorithm = "SHA-256";
          certificateHash = first.expect(OCTET_STRING).content();
        }
      }
    }
    if (!tstContentType) {
      throw new Error("the signed attributes do not name a TSTInfo");
    }
    const signatureAlgorithm = field(signer, 4).child(0).oid();
    const signature = field(signer, 5).expect(OCTET_STRING).content();
    const info = read(content).expect(SEQUENCE).children();
    const policy = field(info, 1).oid();
    const imprint = field(info, 2).expect(SEQUENCE);
    const hashAlgorithm = digestName(imprint.child(0).child(0).oid());
    const serial = field(info, 3).integer();
    const genTime = field(info, 4).generalizedTime();
    let nonce: bigint | null = null;
    for (const item of info.slice(5)) {
      if (item.tag === INTEGER) {
        nonce = item.integer();
      }
    }
    return new TimestampToken(encoded, hashAlgorithm, imprint.child(1).expect(OCTET_STRING).content(), genTime,
      serial, nonce, policy, certificates, byKeyId ? sid.content() : sid.encoded(), byKeyId, digestAlgorithm,
      signedAttributes, messageDigest, certificateHash, certificateHashAlgorithm, signatureAlgorithm, signature,
      content);
  }

  get genTime(): string {
    return instantText(this.genInstant);
  }

  private get genMillis(): number {
    return this.genInstant.seconds * 1000 + Math.floor(this.genInstant.nanos / 1_000_000);
  }

  get imprintHex(): string {
    return toHex(this.messageImprint);
  }

  async verify(expectedSha256: Uint8Array, trusted: X509Certificate[] = []): Promise<TimestampVerification> {
    try {
      if (this.hashAlgorithm !== "SHA-256" || !equal(this.messageImprint, expectedSha256)) {
        return invalid("the timestamp is for different data");
      }
      if (this.messageDigestAttribute === null
        || !equal(this.messageDigestAttribute, await digest(this.digestAlgorithm, this.content))) {
        return invalid("the timestamp signature does not cover its content");
      }
      const signer = this.signer(trusted);
      if (signer === null) {
        return invalid("the timestamp does not carry the certificate of its signer");
      }
      if (this.signingCertificateHash !== null && !equal(this.signingCertificateHash,
        await digest(this.signingCertificateHashAlgorithm!, signer.encoded))) {
        return invalid("the timestamp names a different signing certificate");
      }
      if (signer.extendedKeyUsage === null || !signer.extendedKeyUsage.includes(TIME_STAMPING)) {
        return invalid("the signing certificate is not for timestamping");
      }
      if (!(await signer.verifySignature(this.algorithm(), this.signedAttributes, this.signature))) {
        return invalid("the timestamp signature is not valid");
      }
      signer.checkValidity(this.genMillis);
      const pinned = trusted.length > 0;
      if (pinned && !(await this.chainsTo(signer, trusted))) {
        return invalid("the timestamp authority is not trusted");
      }
      return { valid: true, genTime: this.genTime, authority: signer.subjectName, pinned, problem: null };
    } catch (e) {
      return invalid("the timestamp cannot be verified: " + (e as Error).message);
    }
  }

  private algorithm(): Algorithm {
    const known = SIGNATURES[this.signatureAlgorithm];
    if (known) {
      return known;
    }
    if (this.signatureAlgorithm === RSA_ENCRYPTION) {
      return { kind: "RSA", hash: this.digestAlgorithm };
    }
    if (this.signatureAlgorithm === EC_PUBLIC_KEY) {
      return { kind: "ECDSA", hash: this.digestAlgorithm };
    }
    throw new Error("unsupported signature algorithm " + this.signatureAlgorithm);
  }

  private signer(trusted: X509Certificate[]): X509Certificate | null {
    for (const certificate of [...this.certificates, ...trusted]) {
      if (this.signerByKeyId) {
        if (certificate.subjectKeyIdentifier !== null && equal(certificate.subjectKeyIdentifier,
          this.signerIdentifier)) {
          return certificate;
        }
      } else {
        const sid = read(this.signerIdentifier);
        const issuer = sid.child(0).encoded();
        const serial = sid.child(1).integer();
        if (equal(certificate.issuer.encoded, issuer) && certificate.serialNumber === serial) {
          return certificate;
        }
      }
    }
    return null;
  }

  private async chainsTo(certificate: X509Certificate, trusted: X509Certificate[]): Promise<boolean> {
    let current: X509Certificate | null = certificate;
    for (let depth = 0; depth < MAX_CHAIN_DEPTH && current !== null; depth++) {
      for (const anchor of trusted) {
        if (anchor.equals(current) || (await this.issued(anchor, current))) {
          return true;
        }
      }
      let next: X509Certificate | null = null;
      for (const candidate of this.certificates) {
        if (!candidate.equals(current) && (await this.issued(candidate, current))) {
          next = candidate;
          break;
        }
      }
      current = next;
    }
    return false;
  }

  private async issued(issuer: X509Certificate, subject: X509Certificate): Promise<boolean> {
    if (!issuer.subject.equals(subject.issuer)) {
      return false;
    }
    if (!(await subject.verifiedBy(issuer))) {
      return false;
    }
    try {
      issuer.checkValidity(this.genMillis);
      return true;
    } catch {
      return false;
    }
  }
}

function field(nodes: Node[], index: number): Node {
  const node = nodes[index];
  if (!node) {
    throw new Error(`Index ${index} out of bounds for length ${nodes.length}`);
  }
  return node;
}

export function parseTimestampToken(input: Uint8Array | string): TimestampToken {
  return TimestampToken.parse(input);
}

export function parseTimestampResponse(response: Uint8Array): TimestampToken {
  return TimestampToken.fromResponse(response);
}

export async function verifyTimestamp(
  token: TimestampToken | Uint8Array | string,
  expectedSha256: Uint8Array,
  trusted: X509Certificate[] = [],
): Promise<TimestampVerification> {
  const parsed = token instanceof TimestampToken ? token : TimestampToken.parse(token);
  return parsed.verify(expectedSha256, trusted);
}
