import { concat, equal, fromBase64, fromHex, toBase64, toHex } from "./bytes.js";
import { canonicalBytes, sha256 } from "./canonical.js";
import { formatInstant } from "./timestamps.js";

export const ALGORITHM = "Ed25519";
export const ROTATION_FORMAT = "nexusphere-ledger/key-rotation/v1";

const SPKI_PREFIX = fromHex("302a300506032b6570032100");
const PKCS8_PREFIX = fromHex("302e020100300506032b657004220420");

export class PublicKey {
  private constructor(
    private readonly key: CryptoKey,
    readonly raw: Uint8Array,
    readonly der: Uint8Array,
    readonly keyId: string,
  ) {}

  static async fromRaw(raw: Uint8Array): Promise<PublicKey> {
    if (raw.length !== 32) {
      throw new Error("An Ed25519 key has 32 bytes");
    }
    const key = await crypto.subtle.importKey("raw", raw as BufferSource, { name: ALGORITHM }, true, ["verify"]);
    const der = concat(SPKI_PREFIX, raw);
    return new PublicKey(key, raw, der, toHex(await sha256(der)).substring(0, 16));
  }

  static async fromBase64(text: string): Promise<PublicKey> {
    let der: Uint8Array;
    try {
      der = fromBase64(text.trim());
    } catch {
      throw new Error("Not a base64 X.509 Ed25519 public key");
    }
    if (der.length !== 44 || !equal(der.subarray(0, 12), SPKI_PREFIX)) {
      throw new Error("Not a base64 X.509 Ed25519 public key");
    }
    return PublicKey.fromRaw(der.slice(12));
  }

  get encoded(): string {
    return toBase64(this.der);
  }

  async verify(data: Uint8Array, signature: Uint8Array): Promise<boolean> {
    if (signature.length !== 64) {
      return false;
    }
    try {
      return await crypto.subtle.verify(ALGORITHM, this.key, signature as BufferSource, data as BufferSource);
    } catch {
      return false;
    }
  }

  async verifyBase64(data: Uint8Array, signature: string): Promise<boolean> {
    let raw: Uint8Array;
    try {
      raw = fromBase64(signature);
    } catch {
      return false;
    }
    return this.verify(data, raw);
  }

  equals(other: PublicKey): boolean {
    return equal(this.raw, other.raw);
  }
}

export class PrivateKey {
  private constructor(
    private readonly key: CryptoKey,
    readonly publicKey: PublicKey,
    private readonly pkcs8: Uint8Array,
  ) {}

  static async generate(): Promise<PrivateKey> {
    const pair = (await crypto.subtle.generateKey({ name: ALGORITHM }, true, ["sign", "verify"])) as CryptoKeyPair;
    const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
    return PrivateKey.fromPkcs8(pkcs8);
  }

  static async fromSeed(seed: Uint8Array): Promise<PrivateKey> {
    if (seed.length !== 32) {
      throw new Error("An Ed25519 seed has 32 bytes");
    }
    return PrivateKey.fromPkcs8(concat(PKCS8_PREFIX, seed));
  }

  static async fromBase64(text: string): Promise<PrivateKey> {
    try {
      return await PrivateKey.fromPkcs8(fromBase64(text.trim()));
    } catch {
      throw new Error("Not a base64 PKCS#8 Ed25519 private key");
    }
  }

  static async fromPkcs8(pkcs8: Uint8Array): Promise<PrivateKey> {
    const key = await crypto.subtle.importKey("pkcs8", pkcs8 as BufferSource, { name: ALGORITHM }, true, ["sign"]);
    const jwk = await crypto.subtle.exportKey("jwk", key);
    const raw = fromBase64(base64UrlToBase64(jwk.x as string));
    return new PrivateKey(key, await PublicKey.fromRaw(raw), pkcs8);
  }

  get encoded(): string {
    return toBase64(this.pkcs8);
  }

  get keyId(): string {
    return this.publicKey.keyId;
  }

  async sign(data: Uint8Array): Promise<Uint8Array> {
    return new Uint8Array(await crypto.subtle.sign(ALGORITHM, this.key, data as BufferSource));
  }

  async signBase64(data: Uint8Array): Promise<string> {
    return toBase64(await this.sign(data));
  }
}

function base64UrlToBase64(text: string): string {
  const plain = text.replace(/-/g, "+").replace(/_/g, "/");
  return plain + "=".repeat((4 - (plain.length % 4)) % 4);
}

export interface KeyRotationFields {
  keyId: string;
  publicKey: string;
  previousKeyId: string;
  activatedAt: string;
  keySignature: string;
  previousKeySignature?: string | null;
}

export class KeyRotation {
  readonly keyId: string;
  readonly publicKey: string;
  readonly previousKeyId: string;
  readonly activatedAt: string;
  readonly keySignature: string;
  readonly previousKeySignature: string | null;

  constructor(fields: KeyRotationFields) {
    for (const name of ["keyId", "publicKey", "previousKeyId", "activatedAt", "keySignature"] as const) {
      if (typeof fields[name] !== "string") {
        throw new Error("A key rotation needs " + name);
      }
    }
    this.keyId = fields.keyId;
    this.publicKey = fields.publicKey;
    this.previousKeyId = fields.previousKeyId;
    this.activatedAt = formatInstant(fields.activatedAt);
    this.keySignature = fields.keySignature;
    this.previousKeySignature = fields.previousKeySignature ?? null;
  }

  static async issue(
    key: PrivateKey,
    previousKeyId: string,
    previousKey: PrivateKey | null,
    activatedAt: string,
  ): Promise<KeyRotation> {
    const content = rotationBytes(key.keyId, key.publicKey.encoded, previousKeyId, activatedAt);
    return new KeyRotation({
      keyId: key.keyId,
      publicKey: key.publicKey.encoded,
      previousKeyId,
      activatedAt,
      keySignature: await key.signBase64(content),
      previousKeySignature: previousKey ? await previousKey.signBase64(content) : null,
    });
  }

  get endorsed(): boolean {
    return this.previousKeySignature !== null;
  }

  async key(): Promise<PublicKey | null> {
    try {
      const key = await PublicKey.fromBase64(this.publicKey);
      return key.keyId === this.keyId ? key : null;
    } catch {
      return null;
    }
  }

  async verifiedByKey(): Promise<boolean> {
    const key = await this.key();
    return key !== null && (await key.verifyBase64(this.content(), this.keySignature));
  }

  async verifiedByPrevious(previous: PublicKey): Promise<boolean> {
    return (
      this.endorsed &&
      previous.keyId === this.previousKeyId &&
      (await this.verifiedByKey()) &&
      (await previous.verifyBase64(this.content(), this.previousKeySignature as string))
    );
  }

  private content(): Uint8Array {
    return rotationBytes(this.keyId, this.publicKey, this.previousKeyId, this.activatedAt);
  }
}

export function rotationBytes(keyId: string, publicKey: string, previousKeyId: string, activatedAt: string): Uint8Array {
  return canonicalBytes({
    format: ROTATION_FORMAT,
    keyId,
    algorithm: ALGORITHM,
    publicKey,
    previousKeyId,
    activatedAt: formatInstant(activatedAt),
  });
}

export async function trustedKeys(
  pinned: PublicKey,
  keys: Iterable<PublicKey>,
  rotations: KeyRotation[],
): Promise<Map<string, PublicKey>> {
  const known = new Map<string, PublicKey>();
  for (const key of keys) {
    known.set(key.keyId, key);
  }
  const trusted = new Map<string, PublicKey>([[pinned.keyId, pinned]]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const rotation of rotations) {
      if (!(await rotation.verifiedByKey())) {
        continue;
      }
      const previous = known.get(rotation.previousKeyId);
      if (trusted.has(rotation.keyId) && previous && !trusted.has(previous.keyId)) {
        trusted.set(previous.keyId, previous);
        changed = true;
      }
      const endorser = trusted.get(rotation.previousKeyId);
      if (endorser && !trusted.has(rotation.keyId) && (await rotation.verifiedByPrevious(endorser))) {
        trusted.set(rotation.keyId, (await rotation.key()) as PublicKey);
        changed = true;
      }
    }
  }
  return trusted;
}
