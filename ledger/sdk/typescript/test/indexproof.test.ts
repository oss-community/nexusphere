import { describe, expect, it } from "vitest";
import { GENESIS, Json, PrivateKey, verifyIndexProof } from "../src/index.js";
import { LEDGER_KEY, keyRecord, vector } from "./support.js";

const PINNED = LEDGER_KEY.publicKey.encoded;

function proof(type: string): Json {
  return structuredClone(vector("index-proof.json").proofs.find((p: Json) => (p.term as Json).type === type));
}

async function problems(given: unknown, pkg?: unknown): Promise<string[]> {
  return (await verifyIndexProof(given, { pinnedPublicKey: PINNED, package: pkg })).problems;
}

describe("verifyIndexProof", () => {
  it("shows how often a term occurs or that it never did", async () => {
    const present = await verifyIndexProof(proof("agent"), { pinnedPublicKey: PINNED });
    const absent = await verifyIndexProof(proof("grant"), { pinnedPublicKey: PINNED });
    expect(present.problems).toEqual([]);
    expect(present.present).toBe(true);
    expect(present.count).toBe(2);
    expect(present.lastSequence).toBe(2);
    expect(absent.problems).toEqual([]);
    expect(absent.present).toBe(false);
    expect(absent.count).toBeNull();
    expect(absent.sequence).toBe(3);
  });

  it("does not lead to the signed root when the leaf or the term changes", async () => {
    const fewer = proof("agent");
    (fewer.leaf as Json).count = 1;
    const hidden = proof("agent");
    hidden.leaf = null;
    const other = proof("agent");
    (other.term as Json).agentId = "sales-agent";
    expect(await problems(fewer)).toEqual(["the proof does not lead to the signed root"]);
    expect(await problems(hidden)).toEqual(["the proof does not lead to the signed root"]);
    expect(await problems(other)).toContain("the key does not belong to the term");
  });

  it("needs an index signed by a trusted key for the checkpoint of the package", async () => {
    const input = proof("input");
    const untrusted = await verifyIndexProof(input, {
      pinnedPublicKey: (await PrivateKey.generate()).publicKey.encoded,
    });
    expect(untrusted.problems.some((p) => p.includes("which the proof does not trust"))).toBe(true);
    expect(await problems(input, { checkpoint: { sequence: 2, headHash: GENESIS } })).toEqual([
      "the index is for another checkpoint than the package",
    ]);
    const index = input.index as Json;
    expect(await problems(input, { checkpoint: { sequence: 3, headHash: index.headHash } })).toEqual([]);
    index.root = GENESIS;
    expect(await problems(input)).toEqual([
      "the signature of the index is not valid",
      "the proof does not lead to the signed root",
    ]);
    input.format = "other";
    expect(await problems(input)).toEqual(["unknown index proof format other"]);
  });

  it("refuses an unknown term", async () => {
    const given = proof("agent");
    given.term = { type: "principal", principalId: "alice" };
    expect(await problems(given)).toEqual(["the term is not valid: type must be one of agent, action, input or grant"]);
    given.term = { type: "action", agentId: "sales-agent" };
    expect(await problems(given)).toEqual(["the term is not valid: a action term has only type and agentId, action"]);
    given.term = { type: "agent", agentId: "" };
    expect(await problems(given)).toEqual(["the term is not valid: agentId is required for a agent term"]);
  });

  it("checks the leaf, the other leaf, the siblings and the index", async () => {
    const late = proof("agent");
    (late.leaf as Json).lastSequence = 4;
    expect(await problems(late)).toEqual([
      "the proof cannot be read: the leaf is not a valid count with its first and last sequence",
    ]);
    const named = proof("agent");
    named.otherLeaf = proof("grant").otherLeaf;
    expect(await problems(named)).toEqual(["the proof cannot be read: a proof of a leaf cannot name another leaf"]);
    const same = proof("grant");
    (same.otherLeaf as Json).key = same.key;
    expect(await problems(same)).toEqual([
      "the proof cannot be read: the other leaf of a proof of absence has the same key",
    ]);
    const astray = proof("grant");
    (astray.otherLeaf as Json).key = "ff".repeat(32);
    expect(await problems(astray)).toEqual(["the proof cannot be read: the other leaf is not on the path of the key"]);
    const upper = proof("grant");
    (upper.siblings as string[])[0] = (upper.siblings as string[])[0].toUpperCase();
    expect(await problems(upper)).toEqual(["the proof cannot be read: a hash must be a lowercase hex SHA-256"]);
    const deep = proof("grant");
    deep.otherLeaf = null;
    deep.siblings = Array(257).fill(GENESIS);
    expect(await problems(deep)).toEqual(["the proof cannot be read: a proof has at most 256 siblings"]);
    const scalar = proof("grant");
    scalar.leaf = 7;
    expect(await problems(scalar)).toEqual(["the proof cannot be read: the leaf must be an object or null"]);
    const unread = proof("agent");
    (unread.index as Json).format = "nexusphere-ledger/index/v0";
    expect(await problems(unread)).toEqual(["the index cannot be read: unknown index format nexusphere-ledger/index/v0"]);
  });

  it("refuses a revoked key and takes the key from the proof when none is pinned", async () => {
    const given = proof("agent");
    const unpinned = await verifyIndexProof(given);
    expect(unpinned.valid).toBe(true);
    const revoked = {
      ...keyRecord(LEDGER_KEY),
      revocation: {
        compromisedAt: "2026-10-01T08:00:00Z",
        revokedAt: "2026-10-02T08:00:00Z",
        reason: "compromised",
        revokerKeyId: LEDGER_KEY.keyId,
        signature: "AAAA",
      },
    };
    expect((await verifyIndexProof(given, { keyList: [revoked] })).problems).toEqual([
      `the index is signed with revoked key ${LEDGER_KEY.keyId}`,
    ]);
  });
});
