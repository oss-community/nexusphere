import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  Checkpoint,
  COSIGNATURE,
  ED25519,
  EvidenceStatement,
  GENESIS,
  Json,
  KeyRotation,
  LogCheckpoint,
  LogReceipt,
  NoteKey,
  PrivateKey,
  contentHash,
  cosign,
  fromHex,
  linkHash,
  merkle,
  toBase64,
} from "../src/index.js";

const here = dirname(fileURLToPath(import.meta.url));
export const VECTORS = process.env.NEXUSPHERE_VECTORS ?? resolve(here, "../../../conformance/vectors");

export const ISSUER = "https://ledger.example";
export const ORIGIN = "ledger.example";
export const WITNESS = "witness.example";

export const LEDGER_KEY = await PrivateKey.fromSeed(
  fromHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"),
);
export const WITNESS_KEY = await PrivateKey.fromSeed(
  fromHex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"),
);

export function vector(name: string): any {
  return JSON.parse(readFileSync(resolve(VECTORS, name), "utf8"));
}

export function witnessNoteKey(): Promise<NoteKey> {
  return NoteKey.of(WITNESS, COSIGNATURE, WITNESS_KEY.publicKey);
}

export async function entries(count: number, agents = ["invoice-agent", "sales-agent"], principals = ["acme", "globex"]) {
  const chain: Json[] = [];
  let previous = GENESIS;
  for (let index = 0; index < count; index++) {
    const sequence = index + 1;
    const second = String(index).padStart(2, "0");
    const entry: Json = {
      format: "nexusphere-ledger/evidence/v1",
      id: "00000000-0000-4000-8000-" + String(sequence).padStart(12, "0"),
      sequence,
      occurredAt: `2026-10-01T08:00:${second}.123456Z`,
      recordedAt: `2026-10-01T08:00:${second}.128456Z`,
      agentId: agents[index % agents.length],
      principalId: principals[index % principals.length],
      action: "tools/call",
      target: "read_invoice",
      decision: "ALLOW",
      reason: null,
      delegationId: null,
      inputHash: null,
      outputHash: null,
      outcome: "SUCCEEDED",
      correlationId: null,
      attributes: { tool: "read_invoice" },
      previousHash: previous,
    };
    entry.hash = await linkHash(sequence, previous, await contentHash(entry));
    previous = entry.hash as string;
    chain.push(entry);
  }
  return chain;
}

export async function checkpoint(sequence: number, headHash: string, key = LEDGER_KEY) {
  const createdAt = "2026-10-01T09:00:00.000001Z";
  const signed = await new Checkpoint(sequence, headHash, createdAt, key.keyId).sign(key);
  return {
    format: "nexusphere-ledger/checkpoint/v1",
    sequence,
    headHash,
    createdAt,
    keyId: key.keyId,
    signature: signed.signature,
  };
}

export function keyRecord(key: PrivateKey, status = "ACTIVE", rotation?: KeyRotation): Json {
  return {
    keyId: key.keyId,
    algorithm: "Ed25519",
    publicKey: key.publicKey.encoded,
    status,
    activatedAt: rotation ? rotation.activatedAt : "2026-10-01T07:00:00Z",
    retiredAt: null,
    rotation: rotation
      ? {
          format: "nexusphere-ledger/key-rotation/v1",
          previousKeyId: rotation.previousKeyId,
          keySignature: rotation.keySignature,
          previousKeySignature: rotation.previousKeySignature,
        }
      : null,
  };
}

export async function buildPackage(
  chain: Json[],
  disclose: number[],
  options: { key?: PrivateKey; keys?: Json[]; anchor?: number; scope?: Json; witness?: boolean; log?: boolean } = {},
) {
  const key = options.key ?? LEDGER_KEY;
  const anchor = options.anchor ?? 0;
  const size = chain.length;
  const leaves = await Promise.all(chain.map((e) => merkle.leafHash(fromHex(e.hash as string))));
  const links = [];
  for (const entry of chain.slice(anchor)) {
    links.push({
      sequence: entry.sequence,
      previousHash: entry.previousHash,
      contentHash: await contentHash(entry),
      hash: entry.hash,
      entry: disclose.includes(entry.sequence as number) ? { ...entry } : null,
    });
  }
  const pkg: Json = {
    format: "nexusphere-ledger/package/v1",
    createdAt: "2026-10-01T09:00:01Z",
    scope: options.scope ?? { agentId: null, principalId: null, fromSequence: 1, toSequence: size },
    keys: options.keys ?? [keyRecord(key)],
    anchor: anchor ? await checkpoint(anchor, chain[anchor - 1].hash as string, key) : null,
    checkpoint: await checkpoint(size, chain[size - 1].hash as string, key),
    disclosed: disclose.length,
    links,
    log: null,
  };
  if (options.log ?? true) {
    const root = await merkle.root(leaves);
    let note = await new LogCheckpoint(ORIGIN, size, root).sign(await NoteKey.of(ORIGIN, ED25519, key.publicKey), key);
    if (options.witness) {
      note = note.withSignature(await cosign(note.body, await witnessNoteKey(), WITNESS_KEY, 1760000000));
    }
    const proofs = [];
    for (const sequence of [...disclose].sort((a, b) => a - b)) {
      const entry = chain[sequence - 1];
      const path = await merkle.inclusionProof(leaves, sequence - 1);
      proofs.push({
        sequence,
        hashes: path.map(toBase64),
        statement: toBase64(await EvidenceStatement.sign(entry, ISSUER, key.keyId, key)),
        receipt: toBase64(await LogReceipt.sign(ORIGIN, key.keyId, size, sequence - 1, path, root, key)),
      });
    }
    pkg.log = { checkpoint: note.text(), proofs };
  }
  return pkg;
}
