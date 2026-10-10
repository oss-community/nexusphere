import { canonicalBytes, sha256Hex } from "./canonical.js";
import { PrivateKey, PublicKey } from "./keys.js";
import { formatInstant } from "./timestamps.js";

export const FORMAT = "nexusphere-ledger/evidence/v1";
export const CHECKPOINT_FORMAT = "nexusphere-ledger/checkpoint/v1";
export const CHECKPOINT_FORMAT_WITH_PROFILES = "nexusphere-ledger/checkpoint/v2";

export interface ComplianceProfileRef {
  id: string;
  digest: string;
}
export const GENESIS = "0".repeat(64);

export const CONTENT_FIELDS = [
  "agentId",
  "principalId",
  "action",
  "target",
  "decision",
  "reason",
  "delegationId",
  "inputHash",
  "outputHash",
  "outcome",
  "correlationId",
] as const;

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export type Json = Record<string, unknown>;

export function isSha256(value: unknown): boolean {
  return typeof value === "string" && /^[0-9a-f]{64}$/.test(value);
}

export function canonicalContent(entry: Json): Json {
  const id = required(entry, "id");
  if (!UUID.test(id)) {
    throw new Error("id is not a UUID");
  }
  const content: Json = {
    format: FORMAT,
    id: id.toLowerCase(),
    occurredAt: formatInstant(required(entry, "occurredAt")),
    recordedAt: formatInstant(required(entry, "recordedAt")),
  };
  for (const field of CONTENT_FIELDS) {
    const value = entry[field];
    content[field] = typeof value === "string" ? value : null;
  }
  const attributes: Record<string, string> = {};
  const given = entry.attributes;
  if (given && typeof given === "object") {
    for (const [key, value] of Object.entries(given as Json)) {
      attributes[key] = text(value);
    }
  }
  content.attributes = attributes;
  return content;
}

export function contentHash(entry: Json): Promise<string> {
  return sha256Hex(canonicalBytes(canonicalContent(entry)));
}

export function linkHash(sequence: number, previousHash: string, contentHash: string): Promise<string> {
  return sha256Hex(canonicalBytes({ format: FORMAT, sequence, previousHash, contentHash }));
}

export async function entryHash(entry: Json): Promise<string> {
  return linkHash(long(entry.sequence), required(entry, "previousHash"), await contentHash(entry));
}

export class EvidenceLink {
  constructor(
    readonly sequence: number,
    readonly previousHash: string,
    readonly contentHash: string,
    readonly hash: string,
  ) {}

  static of(node: Json): EvidenceLink {
    return new EvidenceLink(long(node.sequence), required(node, "previousHash"), required(node, "contentHash"),
      required(node, "hash"));
  }

  static async ofEntry(entry: Json): Promise<EvidenceLink> {
    return new EvidenceLink(long(entry.sequence), required(entry, "previousHash"), await contentHash(entry),
      required(entry, "hash"));
  }

  computeHash(): Promise<string> {
    return linkHash(this.sequence, this.previousHash, this.contentHash);
  }
}

export interface ChainVerification {
  valid: boolean;
  checkedEntries: number;
  lastSequence: number;
  lastHash: string;
  failedSequence: number | null;
  failure: string | null;
}

export class ChainVerifier {
  private expectedSequence: number;
  private expectedPreviousHash: string;
  private checked = 0;
  private failure: ChainVerification | null = null;

  constructor(firstSequence = 1, previousHash = GENESIS) {
    this.expectedSequence = firstSequence;
    this.expectedPreviousHash = previousHash;
  }

  async accept(input: EvidenceLink | Json): Promise<boolean> {
    const link = input instanceof EvidenceLink ? input : await EvidenceLink.ofEntry(input);
    if (this.failure) {
      return false;
    }
    if (link.sequence !== this.expectedSequence) {
      return this.fail(link, `expected sequence ${this.expectedSequence} but found ${link.sequence}`);
    }
    if (this.expectedPreviousHash !== link.previousHash) {
      return this.fail(link, `previous hash does not match the hash of sequence ${this.expectedSequence - 1}`);
    }
    const recomputed = await link.computeHash();
    if (recomputed !== link.hash) {
      return this.fail(link, "content does not match its hash");
    }
    this.checked++;
    this.expectedSequence++;
    this.expectedPreviousHash = recomputed;
    return true;
  }

  result(): ChainVerification {
    return (
      this.failure ?? {
        valid: true,
        checkedEntries: this.checked,
        lastSequence: this.expectedSequence - 1,
        lastHash: this.expectedPreviousHash,
        failedSequence: null,
        failure: null,
      }
    );
  }

  private fail(link: EvidenceLink, reason: string): boolean {
    this.failure = {
      valid: false,
      checkedEntries: this.checked,
      lastSequence: this.expectedSequence - 1,
      lastHash: this.expectedPreviousHash,
      failedSequence: link.sequence,
      failure: reason,
    };
    return false;
  }
}

export async function verifyChain(entries: Iterable<Json>): Promise<ChainVerification> {
  const verifier = new ChainVerifier();
  for (const entry of entries) {
    if (!(await verifier.accept(entry))) {
      break;
    }
  }
  return verifier.result();
}

export class Checkpoint {
  constructor(
    readonly sequence: number,
    readonly headHash: string,
    readonly createdAt: string,
    readonly keyId: string,
    readonly signature: string | null = null,
    readonly profiles: ComplianceProfileRef[] | null = null,
  ) {
    this.createdAt = formatInstant(createdAt);
    this.profiles = profiles === null ? null
      : [...profiles].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  }

  static of(node: Json): Checkpoint {
    let profiles: ComplianceProfileRef[] | null = null;
    if (node.profiles !== undefined && node.profiles !== null) {
      if (!Array.isArray(node.profiles)) {
        throw new Error("profiles must be a list");
      }
      profiles = node.profiles.map((p) => ({ id: required(p, "id"), digest: required(p, "digest") }));
    }
    return new Checkpoint(long(node.sequence), required(node, "headHash"), required(node, "createdAt"),
      required(node, "keyId"), typeof node.signature === "string" ? node.signature : null, profiles);
  }

  get format(): string {
    return this.profiles === null ? CHECKPOINT_FORMAT : CHECKPOINT_FORMAT_WITH_PROFILES;
  }

  signedBytes(): Uint8Array {
    const content: Json = {
      format: this.format,
      sequence: this.sequence,
      headHash: this.headHash,
      createdAt: this.createdAt,
      keyId: this.keyId,
    };
    if (this.profiles !== null) {
      content.profiles = this.profiles.map((p) => ({ id: p.id, digest: p.digest }));
    }
    return canonicalBytes(content);
  }

  async sign(key: PrivateKey): Promise<Checkpoint> {
    return new Checkpoint(this.sequence, this.headHash, this.createdAt, this.keyId,
      await key.signBase64(this.signedBytes()), this.profiles);
  }

  async verify(key: PublicKey): Promise<boolean> {
    return this.signature !== null && key.keyId === this.keyId && key.verifyBase64(this.signedBytes(), this.signature);
  }
}

export function required(node: unknown, field: string): string {
  const value = node && typeof node === "object" ? (node as Json)[field] : undefined;
  if (typeof value !== "string") {
    throw new Error(field + " is missing");
  }
  return value;
}

export function nullable(node: unknown, field: string): string | null {
  const value = node && typeof node === "object" ? (node as Json)[field] : undefined;
  return typeof value === "string" ? value : null;
}

export function long(value: unknown, fallback = 0): number {
  return typeof value === "number" && Number.isFinite(value) ? Math.trunc(value) : fallback;
}

function text(value: unknown): string {
  if (typeof value === "string") {
    return value;
  }
  if (value === null || value === undefined) {
    return "";
  }
  return String(value);
}
