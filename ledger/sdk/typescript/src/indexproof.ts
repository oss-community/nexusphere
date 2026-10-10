import { concat, equal, fromHex, toHex } from "./bytes.js";
import { canonicalBytes, sha256 } from "./canonical.js";
import { Json, isSha256 } from "./evidence.js";
import { packageTrustedKeys } from "./package.js";
import { formatInstant } from "./timestamps.js";

export const INDEX_FORMAT = "nexusphere-ledger/index/v1";
export const INDEX_PROOF_FORMAT = "nexusphere-ledger/index-proof/v1";
export const INDEX_DEPTH = 256;
export const INDEX_TERMS = new Map<string, string[]>([
  ["agent", ["agentId"]],
  ["action", ["agentId", "action"]],
  ["input", ["inputHash"]],
  ["grant", ["delegationId"]],
]);

const LEAF = 0;
const NODE = 1;
const EMPTY = new Uint8Array(32);

export interface IndexCheck {
  valid: boolean;
  term: Record<string, string>;
  present: boolean;
  count: number | null;
  firstSequence: number | null;
  lastSequence: number | null;
  sequence: number | null;
  headHash: string | null;
  keyId: string | null;
  problems: string[];
}

export interface IndexProofOptions {
  pinnedPublicKey?: string | null;
  keyList?: Json[] | null;
  package?: unknown;
}

export interface IndexRoot {
  sequence: number;
  headHash: string;
  root: string;
  createdAt: string;
  keyId: string;
}

export function indexTerm(requested: Record<string, unknown>): Record<string, string> {
  const type = requested.type;
  const fields = INDEX_TERMS.get(typeof type === "string" ? type : "");
  if (!fields) {
    throw new Error("type must be one of agent, action, input or grant");
  }
  if (Object.keys(requested).length !== fields.length + 1) {
    throw new Error(`a ${type} term has only type and ${fields.join(", ")}`);
  }
  const term: Record<string, string> = { type: type as string };
  for (const field of fields) {
    const value = requested[field];
    if (typeof value !== "string" || value === "") {
      throw new Error(`${field} is required for a ${type} term`);
    }
    term[field] = value;
  }
  return term;
}

export function indexKey(term: Record<string, string>): Promise<Uint8Array> {
  return sha256(canonicalBytes(term));
}

export function indexValueHash(count: number, firstSequence: number, lastSequence: number): Promise<Uint8Array> {
  return sha256(canonicalBytes({ count, firstSequence, lastSequence }));
}

export function indexLeafHash(key: Uint8Array, valueHash: Uint8Array): Promise<Uint8Array> {
  return sha256(concat(Uint8Array.of(LEAF), key, valueHash));
}

export function indexSignedBytes(index: IndexRoot): Uint8Array {
  return canonicalBytes({
    format: INDEX_FORMAT,
    sequence: index.sequence,
    headHash: index.headHash,
    root: index.root,
    createdAt: formatInstant(index.createdAt),
    keyId: index.keyId,
  });
}

export async function indexRootFrom(
  key: Uint8Array,
  valueHash: Uint8Array | null,
  siblings: Uint8Array[],
  other: { key: Uint8Array; valueHash: Uint8Array } | null,
): Promise<Uint8Array> {
  if (siblings.length > INDEX_DEPTH) {
    throw new Error(`a proof has at most ${INDEX_DEPTH} siblings`);
  }
  const depth = siblings.length;
  let hash: Uint8Array;
  if (valueHash !== null) {
    if (other !== null) {
      throw new Error("a proof of a leaf cannot name another leaf");
    }
    hash = await indexLeafHash(key, valueHash);
  } else if (other !== null) {
    if (equal(other.key, key)) {
      throw new Error("the other leaf of a proof of absence has the same key");
    }
    for (let d = 0; d < depth; d++) {
      if (bit(other.key, d) !== bit(key, d)) {
        throw new Error("the other leaf is not on the path of the key");
      }
    }
    hash = await indexLeafHash(other.key, other.valueHash);
  } else {
    hash = EMPTY;
  }
  for (let d = depth - 1; d >= 0; d--) {
    hash = bit(key, d) === 0 ? await node(hash, siblings[d]) : await node(siblings[d], hash);
  }
  return hash;
}

export async function verifyIndexProof(proof: unknown, options: IndexProofOptions = {}): Promise<IndexCheck> {
  const problems: string[] = [];
  const index = path(proof, "index");
  const sequence = integral(path(index, "sequence"));
  const headHash = text(path(index, "headHash"), null);
  const keyId = text(path(index, "keyId"), null);
  const leaf = path(proof, "leaf");
  const present = isObject(leaf);
  const count = present ? integral(leaf.count) : null;
  const first = present ? integral(leaf.firstSequence) : null;
  const last = present ? integral(leaf.lastSequence) : null;
  let term: Record<string, string> = {};
  const check = (): IndexCheck => ({
    valid: problems.length === 0,
    term,
    present,
    count,
    firstSequence: first,
    lastSequence: last,
    sequence,
    headHash,
    keyId,
    problems,
  });
  if (text(path(proof, "format"), null) !== INDEX_PROOF_FORMAT) {
    problems.push(`unknown index proof format ${text(path(proof, "format"), null)}`);
    return check();
  }
  try {
    const requested: Record<string, unknown> = {};
    const given = path(proof, "term");
    for (const [name, value] of Object.entries(isObject(given) ? given : {})) {
      requested[name] = typeof value === "string" ? value : null;
    }
    term = indexTerm(requested);
  } catch (e) {
    problems.push(`the term is not valid: ${(e as Error).message}`);
    return check();
  }
  const key = await indexKey(term);
  if (toHex(key) !== text(path(proof, "key"), "")) {
    problems.push("the key does not belong to the term");
  }
  let root: IndexRoot;
  try {
    if (text(path(index, "format"), null) !== INDEX_FORMAT || sequence === null) {
      throw new Error(`unknown index format ${text(path(index, "format"), null)}`);
    }
    const rootHash = text(path(index, "root"), null);
    const createdAt = formatInstant(text(path(index, "createdAt"), "")!);
    if (keyId === null) {
      throw new Error("keyId");
    }
    if (!isSha256(headHash) || !isSha256(rootHash)) {
      throw new Error("headHash and root must be lowercase hex SHA-256");
    }
    root = { sequence, headHash: headHash!, root: rootHash!, createdAt, keyId };
  } catch (e) {
    problems.push(`the index cannot be read: ${(e as Error).message}`);
    return check();
  }
  const keyList = options.keyList ?? [];
  const trusted = await packageTrustedKeys(proof as Json, keyList, options.pinnedPublicKey ?? null, problems);
  const signing = trusted.get(root.keyId);
  if (!signing) {
    problems.push(`the index is signed with key ${root.keyId}, which the proof does not trust`);
  } else if (revoked(proof, keyList, root.keyId)) {
    problems.push(`the index is signed with revoked key ${root.keyId}`);
  } else if (!(await signing.verifyBase64(indexSignedBytes(root), text(path(index, "signature"), "")!))) {
    problems.push("the signature of the index is not valid");
  }
  try {
    let valueHash: Uint8Array | null = null;
    if (present) {
      if (
        count === null || first === null || last === null || count < 1 || first < 1 || first > last ||
        last > root.sequence || count > last - first + 1
      ) {
        throw new Error("the leaf is not a valid count with its first and last sequence");
      }
      valueHash = await indexValueHash(count, first, last);
    } else if (leaf !== null && leaf !== undefined) {
      throw new Error("the leaf must be an object or null");
    }
    const siblings = elements(path(proof, "siblings")).map((sibling) => hash(text(sibling, "")!));
    const other = path(proof, "otherLeaf");
    const otherLeaf = isObject(other)
      ? { key: hash(text(other.key, "")!), valueHash: hash(text(other.valueHash, "")!) }
      : null;
    if (!equal(await indexRootFrom(key, valueHash, siblings, otherLeaf), fromHex(root.root))) {
      problems.push("the proof does not lead to the signed root");
    }
  } catch (e) {
    problems.push(`the proof cannot be read: ${(e as Error).message}`);
  }
  if (options.package !== undefined && options.package !== null) {
    const checkpoint = path(options.package, "checkpoint");
    if (
      root.sequence !== asLong(path(checkpoint, "sequence"), -1) ||
      headHash !== text(path(checkpoint, "headHash"), null)
    ) {
      problems.push("the index is for another checkpoint than the package");
    }
  }
  return check();
}

function node(left: Uint8Array, right: Uint8Array): Promise<Uint8Array> {
  return sha256(concat(Uint8Array.of(NODE), left, right));
}

function bit(bytes: Uint8Array, index: number): number {
  return (bytes[index >> 3] >> (7 - (index % 8))) & 1;
}

function hash(hex: string): Uint8Array {
  if (!isSha256(hex)) {
    throw new Error("a hash must be a lowercase hex SHA-256");
  }
  return fromHex(hex);
}

function revoked(proof: unknown, keyList: Json[], keyId: string): boolean {
  const nodes = [...elements(path(proof, "keys")), ...elements(keyList)];
  return nodes.some((node) => keyId === text(path(node, "keyId"), null) && isObject(path(node, "revocation")));
}

function integral(node: unknown): number | null {
  return typeof node === "number" && Number.isSafeInteger(node) ? node : null;
}

function asLong(node: unknown, fallback: number): number {
  if (typeof node === "number" && Number.isFinite(node)) {
    return Math.trunc(node);
  }
  if (typeof node === "string" && /^[+-]?\d+$/.test(node.trim())) {
    return Number(node.trim());
  }
  return fallback;
}

function text(node: unknown, fallback: string | null): string | null {
  if (typeof node === "string") {
    return node;
  }
  if (typeof node === "number" || typeof node === "boolean") {
    return String(node);
  }
  return node === undefined || node === null ? fallback : "";
}

function elements(node: unknown): unknown[] {
  return Array.isArray(node) ? node : isObject(node) ? Object.values(node) : [];
}

function path(node: unknown, field: string): unknown {
  return isObject(node) ? node[field] : undefined;
}

function isObject(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
