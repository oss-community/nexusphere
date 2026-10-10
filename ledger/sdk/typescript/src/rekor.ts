import { equal, fromBase64, fromUtf8, toBase64, utf8 } from "./bytes.js";
import { PublicKey } from "./keys.js";
import { leafHash, verifyInclusion } from "./merkle.js";
import { Note, NoteKey } from "./note.js";

export const REKOR_KIND = "hashedrekord";
export const REKOR_API_VERSION = "0.0.2";
export const REKOR_KEY_DETAILS = "PKIX_ED25519_PH";

export interface RekorVerification {
  valid: boolean;
  logIndex: number | null;
  origin: string | null;
  pinned: boolean;
  problem: string | null;
}

type Node = Record<string, unknown>;

function invalid(problem: string): RekorVerification {
  return { valid: false, logIndex: null, origin: null, pinned: false, problem };
}

async function sha512(data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-512", data as BufferSource));
}

function bytesOf(data: Uint8Array | string): Uint8Array {
  return typeof data === "string" ? utf8(data) : data;
}

export async function rekorRequest(signedBytes: Uint8Array | string, signature: Uint8Array, key: PublicKey): Promise<string> {
  return JSON.stringify({
    hashedRekordRequestV002: {
      digest: toBase64(await sha512(bytesOf(signedBytes))),
      signature: {
        content: toBase64(signature),
        verifier: { keyDetails: REKOR_KEY_DETAILS, publicKey: { rawBytes: toBase64(key.der) } },
      },
    },
  });
}

export async function verifyRekorEntry(
  entry: unknown,
  signedBytes: Uint8Array | string,
  ledgerKey: PublicKey,
  rekorKeys: (string | NoteKey)[] = [],
): Promise<RekorVerification> {
  const keys = await Promise.all(rekorKeys.map((key) => (typeof key === "string" ? NoteKey.parse(key) : key)));
  let logIndex: number;
  let body: Uint8Array;
  let treeSize: number;
  let root: Uint8Array;
  const hashes: Uint8Array[] = [];
  let note: Note;
  let spec: unknown;
  try {
    logIndex = parseLong(text(entry, "logIndex"));
    body = decodeBase64(text(entry, "canonicalizedBody"));
    const proof = path(entry, "inclusionProof");
    if (parseLong(text(proof, "logIndex")) !== logIndex) {
      return invalid("the inclusion proof is for another entry");
    }
    treeSize = parseLong(text(proof, "treeSize"));
    root = decodeBase64(text(proof, "rootHash"));
    const list = path(proof, "hashes");
    for (const hash of Array.isArray(list) ? list : isObject(list) ? Object.values(list) : []) {
      hashes.push(decodeBase64(typeof hash === "string" ? hash : String(hash)));
    }
    note = Note.parse(text(path(proof, "checkpoint"), "envelope"));
    const parsed: unknown = JSON.parse(fromUtf8(body));
    if (path(parsed, "kind") !== REKOR_KIND || path(parsed, "apiVersion") !== REKOR_API_VERSION) {
      return invalid("the entry is not a hashedrekord 0.0.2");
    }
    spec = path(path(parsed, "spec"), "hashedRekordV002");
  } catch (e) {
    return invalid("the entry cannot be read: " + (e as Error).message);
  }
  const data = path(spec, "data");
  const digest = decode(path(data, "digest"));
  if (path(data, "algorithm") !== "SHA2_512" || digest === null || !equal(digest, await sha512(bytesOf(signedBytes)))) {
    return invalid("the entry is for different data");
  }
  const verifier = path(path(spec, "signature"), "verifier");
  const key = decode(path(path(verifier, "publicKey"), "rawBytes"));
  if (path(verifier, "keyDetails") !== REKOR_KEY_DETAILS || key === null || !equal(key, ledgerKey.der)) {
    return invalid("the entry is signed by another key");
  }
  if (!(await verifyInclusion(await leafHash(body), logIndex, treeSize, hashes, root))) {
    return invalid("the entry is not proven in the log");
  }
  if (note.checkpoint.size !== treeSize || !equal(note.checkpoint.root, root)) {
    return invalid("the log checkpoint does not match the inclusion proof");
  }
  const pinned = keys.length > 0;
  if (pinned && !(await signedByAny(note, keys))) {
    return invalid("the log checkpoint is not signed by a pinned Rekor key");
  }
  return { valid: true, logIndex, origin: note.checkpoint.origin, pinned, problem: null };
}

async function signedByAny(note: Note, keys: NoteKey[]): Promise<boolean> {
  for (const key of keys) {
    if (await note.signedBy(key)) {
      return true;
    }
  }
  return false;
}

function isObject(value: unknown): value is Node {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function path(node: unknown, field: string): unknown {
  return isObject(node) ? node[field] : undefined;
}

function text(node: unknown, field: string): string {
  const value = path(node, field);
  if (typeof value === "number") {
    return String(value);
  }
  if (typeof value !== "string") {
    throw new Error(field + " is missing");
  }
  return value;
}

function parseLong(value: string): number {
  if (!/^[+-]?\d+$/.test(value) || !Number.isSafeInteger(Number(value))) {
    throw new Error(`For input string: "${value}"`);
  }
  return Number(value);
}

function decodeBase64(value: string): Uint8Array {
  const padded = value.length % 4 === 0 ? value : value + "=".repeat(4 - (value.length % 4));
  try {
    return fromBase64(padded);
  } catch {
    throw new Error("Illegal base64 input");
  }
}

function decode(node: unknown): Uint8Array | null {
  if (typeof node !== "string") {
    return null;
  }
  try {
    return decodeBase64(node);
  } catch {
    return null;
  }
}
