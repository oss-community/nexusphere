import { ascii, fromHex, toHex } from "./bytes.js";
import { CborMap, Tagged, decode, encode } from "./cbor.js";
import { Json, contentHash as computeContentHash, linkHash, long, required } from "./evidence.js";
import { PrivateKey, PublicKey } from "./keys.js";
import { leafHash, rootFromInclusion } from "./merkle.js";

export const TAG = 18;
export const ALG = 1;
export const KID = 4;
export const CWT_CLAIMS = 15;
export const VDS = 395;
export const VDP = 396;
export const PAYLOAD_HASH_ALG = 258;
export const PREIMAGE_CONTENT_TYPE = 259;
export const EDDSA = -8;
export const SHA_256 = -16;
export const ISS = 1;
export const SUB = 2;
export const CONTENT_TYPE = "application/vnd.nexusphere.evidence+json";
export const SEQUENCE = "nexusphere-sequence";
export const PREVIOUS_HASH = "nexusphere-previous-hash";
export const RFC9162_SHA256 = 1;
export const INCLUSION_PROOFS = -1;

const EMPTY: CborMap = new Map();

function toBeSigned(protectedBytes: Uint8Array, payload: Uint8Array): Uint8Array {
  return encode(["Signature1", protectedBytes, new Uint8Array(0), payload]);
}

export class CoseSign1 {
  constructor(
    readonly protectedBytes: Uint8Array,
    readonly protectedHeader: CborMap,
    readonly unprotected: CborMap,
    readonly payload: Uint8Array | null,
    readonly signature: Uint8Array,
  ) {}

  static async sign(
    protectedHeader: CborMap,
    unprotected: CborMap,
    payload: Uint8Array,
    detached: boolean,
    key: PrivateKey,
  ): Promise<Uint8Array> {
    const protectedBytes = encode(protectedHeader);
    const signature = await key.sign(toBeSigned(protectedBytes, payload));
    return encode(new Tagged(TAG, [protectedBytes, unprotected, detached ? null : payload, signature]));
  }

  static parse(data: Uint8Array): CoseSign1 {
    let decoded = decode(data);
    if (decoded instanceof Tagged && decoded.tag === TAG) {
      decoded = decoded.value;
    }
    if (
      !Array.isArray(decoded) ||
      decoded.length !== 4 ||
      !(decoded[0] instanceof Uint8Array) ||
      !(decoded[1] instanceof Map) ||
      !(decoded[3] instanceof Uint8Array) ||
      !(decoded[2] === null || decoded[2] instanceof Uint8Array)
    ) {
      throw new Error("Not a COSE_Sign1 message");
    }
    const header = decoded[0].length === 0 ? new Map() : decode(decoded[0]);
    if (!(header instanceof Map)) {
      throw new Error("The COSE protected header is not a map");
    }
    return new CoseSign1(decoded[0], header, decoded[1], decoded[2], decoded[3]);
  }

  async verify(key: PublicKey, detachedPayload: Uint8Array | null = null): Promise<boolean> {
    const signed = this.payload ?? detachedPayload;
    if (!signed || this.protectedHeader.get(ALG) !== EDDSA) {
      return false;
    }
    return key.verify(toBeSigned(this.protectedBytes, signed), this.signature);
  }

  get keyId(): string | null {
    const kid = this.protectedHeader.get(KID);
    return kid instanceof Uint8Array ? String.fromCharCode(...kid) : null;
  }

  get claims(): CborMap {
    const claims = this.protectedHeader.get(CWT_CLAIMS);
    return claims instanceof Map ? claims : EMPTY;
  }
}

export function subjectOf(entry: Json): string {
  return "urn:uuid:" + required(entry, "id");
}

export class EvidenceStatement {
  constructor(
    readonly message: CoseSign1,
    readonly issuer: string,
    readonly subject: string,
    readonly sequence: number,
    readonly previousHash: string,
    readonly contentHash: string,
  ) {}

  static async sign(entry: Json, issuer: string, keyId: string, key: PrivateKey, contentHash?: string): Promise<Uint8Array> {
    const header: CborMap = new Map<number | string, unknown>([
      [ALG, EDDSA],
      [KID, ascii(keyId)],
      [CWT_CLAIMS, new Map<number, unknown>([[ISS, issuer], [SUB, subjectOf(entry)]])],
      [PAYLOAD_HASH_ALG, SHA_256],
      [PREIMAGE_CONTENT_TYPE, CONTENT_TYPE],
      [SEQUENCE, long(entry.sequence)],
      [PREVIOUS_HASH, fromHex(required(entry, "previousHash"))],
    ]);
    return CoseSign1.sign(header, EMPTY, fromHex(contentHash ?? (await computeContentHash(entry))), false, key);
  }

  static parse(data: Uint8Array): EvidenceStatement {
    const message = CoseSign1.parse(data);
    const header = message.protectedHeader;
    const claims = message.claims;
    const sequence = header.get(SEQUENCE);
    const previous = header.get(PREVIOUS_HASH);
    const issuer = claims.get(ISS);
    const subject = claims.get(SUB);
    if (
      header.get(PAYLOAD_HASH_ALG) !== SHA_256 ||
      header.get(PREIMAGE_CONTENT_TYPE) !== CONTENT_TYPE ||
      typeof sequence !== "number" ||
      !(previous instanceof Uint8Array) ||
      previous.length !== 32 ||
      typeof issuer !== "string" ||
      typeof subject !== "string" ||
      !message.payload ||
      message.payload.length !== 32
    ) {
      throw new Error("Not a Nexusphere evidence statement");
    }
    return new EvidenceStatement(message, issuer, subject, sequence, toHex(previous), toHex(message.payload));
  }

  verify(key: PublicKey): Promise<boolean> {
    return this.message.verify(key);
  }

  get keyId(): string | null {
    return this.message.keyId;
  }

  entryHash(): Promise<string> {
    return linkHash(this.sequence, this.previousHash, this.contentHash);
  }

  async leafHash(): Promise<Uint8Array> {
    return leafHash(fromHex(await this.entryHash()));
  }

  describes(link: { sequence: number; previousHash: string; contentHash: string }): boolean {
    return link.sequence === this.sequence && link.previousHash === this.previousHash &&
      link.contentHash === this.contentHash;
  }
}

export class LogReceipt {
  constructor(
    readonly message: CoseSign1,
    readonly issuer: string,
    readonly treeSize: number,
    readonly leafIndex: number,
    readonly path: Uint8Array[],
  ) {}

  static sign(
    issuer: string,
    keyId: string,
    treeSize: number,
    leafIndex: number,
    path: Uint8Array[],
    root: Uint8Array,
    key: PrivateKey,
  ): Promise<Uint8Array> {
    const header: CborMap = new Map<number, unknown>([
      [ALG, EDDSA],
      [KID, ascii(keyId)],
      [VDS, RFC9162_SHA256],
      [CWT_CLAIMS, new Map([[ISS, issuer]])],
    ]);
    const proof = encode([treeSize, leafIndex, [...path]]);
    const unprotected: CborMap = new Map([[VDP, new Map([[INCLUSION_PROOFS, [proof]]])]]);
    return CoseSign1.sign(header, unprotected, root, true, key);
  }

  static parse(data: Uint8Array): LogReceipt {
    const message = CoseSign1.parse(data);
    const proofs = message.unprotected.get(VDP);
    const inclusion = proofs instanceof Map ? proofs.get(INCLUSION_PROOFS) : undefined;
    const issuer = message.claims.get(ISS);
    if (
      message.protectedHeader.get(VDS) !== RFC9162_SHA256 ||
      typeof issuer !== "string" ||
      !Array.isArray(inclusion) ||
      inclusion.length !== 1 ||
      !(inclusion[0] instanceof Uint8Array)
    ) {
      throw new Error("Not an RFC 9162 inclusion receipt");
    }
    const proof = decode(inclusion[0]);
    if (
      !Array.isArray(proof) ||
      proof.length !== 3 ||
      typeof proof[0] !== "number" ||
      typeof proof[1] !== "number" ||
      !Array.isArray(proof[2]) ||
      !proof[2].every((item: unknown) => item instanceof Uint8Array && item.length === 32)
    ) {
      throw new Error("Not an RFC 9162 inclusion receipt");
    }
    return new LogReceipt(message, issuer, proof[0], proof[1], proof[2] as Uint8Array[]);
  }

  root(leaf: Uint8Array): Promise<Uint8Array | null> {
    return rootFromInclusion(leaf, this.leafIndex, this.treeSize, this.path);
  }

  async verify(leaf: Uint8Array, key: PublicKey): Promise<boolean> {
    const computed = await this.root(leaf);
    return computed !== null && this.message.verify(key, computed);
  }

  get keyId(): string | null {
    return this.message.keyId;
  }
}
