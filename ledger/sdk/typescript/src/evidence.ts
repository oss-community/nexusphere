import { canonicalBytes, sha256Hex } from "./canonical.js";
import { PrivateKey, PublicKey } from "./keys.js";
import { formatInstant } from "./timestamps.js";

export const FORMAT = "nexusphere-ledger/evidence/v2";
export const CHECKPOINT_FORMAT = "nexusphere-ledger/checkpoint/v1";
export const CHECKPOINT_FORMAT_WITH_PROFILES = "nexusphere-ledger/checkpoint/v2";

export interface ComplianceProfileRef {
  id: string;
  digest: string;
}
export const GENESIS = "0".repeat(64);

export const CONTENT_FIELDS = [
  "agentId",
  "action",
  "decision",
  "delegationId",
  "inputHash",
  "outputHash",
  "outcome",
] as const;

export const PERSONAL_FIELDS = ["principalId", "target", "reason", "correlationId"] as const;
export const ATTRIBUTE = "attributes.";

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export type Json = Record<string, unknown>;

export function isSha256(value: unknown): boolean {
  return typeof value === "string" && /^[0-9a-f]{64}$/.test(value);
}

export async function canonicalContent(entry: Json): Promise<Json> {
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
  content.commitments = await commitments(entry);
  return content;
}

export async function contentHash(entry: Json): Promise<string> {
  return sha256Hex(canonicalBytes(await canonicalContent(entry)));
}

export function personalValues(entry: Json): Record<string, string | null> {
  const values: Record<string, string | null> = {};
  for (const field of PERSONAL_FIELDS) {
    values[field] = nullableText(entry[field]);
  }
  const given = entry.attributes;
  if (given && typeof given === "object") {
    for (const [name, value] of Object.entries(given as Json)) {
      values[ATTRIBUTE + name] = nullableText(value);
    }
  }
  return values;
}

export function commitment(field: string, salt: string, value: string | null): Promise<string> {
  return sha256Hex(canonicalBytes({ field, salt, value }));
}

export function erased(entry: Json): boolean {
  return !isObject(entry.salts);
}

export async function commitments(entry: Json): Promise<Record<string, string>> {
  if (erased(entry)) {
    const attributes = entry.attributes;
    if (PERSONAL_FIELDS.some((field) => entry[field] !== null && entry[field] !== undefined)
      || (isObject(attributes) && Object.keys(attributes).length > 0)) {
      throw new Error("an erased entry has no personal values");
    }
    const stored = entry.commitments;
    if (!isObject(stored) || !Object.values(stored).every(isSha256)) {
      throw new Error("commitments is missing");
    }
    return { ...(stored as Record<string, string>) };
  }
  const salts = entry.salts as Json;
  const values = personalValues(entry);
  const fields = Object.keys(values).sort();
  if (fields.join("\n") !== Object.keys(salts).sort().join("\n")) {
    throw new Error("the salts must cover exactly " + fields.join(", "));
  }
  const result: Record<string, string> = {};
  for (const field of fields) {
    result[field] = await commitment(field, required(salts, field), values[field]);
  }
  return result;
}

export function newSalts(attributes: Record<string, unknown> = {}): Record<string, string> {
  const salts: Record<string, string> = {};
  for (const field of [...PERSONAL_FIELDS, ...Object.keys(attributes).map((name) => ATTRIBUTE + name)]) {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    salts[field] = btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }
  return salts;
}

function isObject(value: unknown): value is Json {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function nullableText(value: unknown): string | null {
  return value === null || value === undefined ? null : text(value);
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
