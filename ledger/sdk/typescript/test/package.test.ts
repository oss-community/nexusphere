import { describe, expect, it } from "vitest";
import {
  Checkpoint,
  Json,
  KeyRevocation,
  KeyRotation,
  PrivateKey,
  toBase64,
  verifyPackage,
  utf8,
} from "../src/index.js";
import { LEDGER_KEY, buildPackage, entries, keyRecord, witnessNoteKey } from "./support.js";

const PINNED = LEDGER_KEY.publicKey.encoded;

describe("verifyPackage", async () => {
  const chain = await entries(6);

  it("accepts a package with log, receipts and a witness", async () => {
    const report = await verifyPackage(await buildPackage(chain, [2, 4], { witness: true }), PINNED, {
      witnesses: [(await witnessNoteKey()).vkey],
    });
    expect(report.problems).toEqual([]);
    expect(report).toMatchObject({
      valid: true,
      keyId: LEDGER_KEY.keyId,
      pinnedKeyId: LEDGER_KEY.keyId,
      checkedLinks: 6,
      disclosedEntries: 2,
      provenEntries: 2,
      receiptedEntries: 2,
      witnesses: ["witness.example"],
      logOrigin: "ledger.example",
      logTreeSize: 6,
    });
  });

  it("reports the compliance profiles the checkpoint signs", async () => {
    const pkg = await buildPackage(chain, [2]);
    const node = pkg.checkpoint as Json;
    const digest = "ab".repeat(32);
    const signed = await new Checkpoint(node.sequence as number, node.headHash as string, node.createdAt as string,
      node.keyId as string, null, [{ id: "eu", digest }]).sign(LEDGER_KEY);
    Object.assign(node, { format: signed.format, signature: signed.signature, profiles: [{ id: "eu", digest }] });
    const report = await verifyPackage(pkg, PINNED);
    expect(report.problems).toEqual([]);
    expect(report.complianceProfiles).toEqual([{ id: "eu", digest }]);
    (node.profiles as Json[])[0].id = "us";
    expect((await verifyPackage(pkg, PINNED)).problems).toContain("the signature of checkpoint 6 is not valid");
  });

  it("starts at the anchor and keeps to the scope", async () => {
    const scope = { agentId: "invoice-agent", principalId: null, fromSequence: 3, toSequence: 5 };
    const report = await verifyPackage(await buildPackage(chain, [3, 5], { anchor: 2, scope }), PINNED);
    expect(report.problems).toEqual([]);
    expect(report).toMatchObject({ anchorSequence: 2, firstSequence: 3, checkedLinks: 4 });
    const outside = { agentId: "invoice-agent", principalId: null, fromSequence: 1, toSequence: 6 };
    expect((await verifyPackage(await buildPackage(chain, [2], { scope: outside }), PINNED)).problems).toContain(
      "sequence 2: the disclosed entry is outside the scope of the package",
    );
  });

  it("finds changed entries, removed links and forged checkpoints", async () => {
    const changed = (await buildPackage(chain, [3])) as any;
    changed.links[2].entry.target = "delete_invoice";
    expect((await verifyPackage(changed, PINNED)).problems).toContain(
      "sequence 3: the disclosed entry does not match its content hash",
    );
    const removed = (await buildPackage(chain, [1])) as any;
    removed.links.splice(3, 1);
    expect((await verifyPackage(removed, PINNED)).problems).toContain("sequence 5: expected sequence 4 but found 5");
    const forged = (await buildPackage(chain, [1])) as any;
    forged.checkpoint.sequence = 5;
    expect((await verifyPackage(forged, PINNED)).problems).toContain("the signature of checkpoint 5 is not valid");
    const badSignature = (await buildPackage(chain, [1])) as any;
    badSignature.checkpoint.signature = toBase64(utf8("x".repeat(64)));
    expect((await verifyPackage(badSignature, PINNED)).problems).toContain("the signature of checkpoint 6 is not valid");
  });

  it("trusts only the pinned key and keys it endorsed", async () => {
    const other = await PrivateKey.generate();
    const report = await verifyPackage(await buildPackage(chain, [1], { key: other }), PINNED);
    expect(report.valid).toBe(false);
    expect(report.problems[0]).toMatch(new RegExp("^checkpoint 6 is signed with key " + other.keyId));
    expect((await verifyPackage(await buildPackage(chain, [1], { key: other }))).valid).toBe(true);
    const rotation = await KeyRotation.issue(other, LEDGER_KEY.keyId, LEDGER_KEY, "2026-10-01T08:30:00Z");
    const keys = [keyRecord(LEDGER_KEY, "RETIRED"), keyRecord(other, "ACTIVE", rotation)];
    const rotated = await verifyPackage(await buildPackage(chain, [1], { key: other, keys }), PINNED);
    expect(rotated.problems).toEqual([]);
    expect(rotated.keyId).toBe(other.keyId);
    const unendorsed = await KeyRotation.issue(other, LEDGER_KEY.keyId, null, "2026-10-01T08:30:00Z");
    const alone = [keyRecord(LEDGER_KEY, "RETIRED"), keyRecord(other, "ACTIVE", unendorsed)];
    expect((await verifyPackage(await buildPackage(chain, [1], { key: other, keys: alone }), PINNED)).valid).toBe(false);
  });

  it("checks the log section", async () => {
    const missing = await verifyPackage(await buildPackage(chain, [1]), PINNED, {
      witnesses: [(await witnessNoteKey()).vkey],
    });
    expect(missing.problems).toContain("the log checkpoint is cosigned by 0 of the 1 required witnesses");
    const wrongProof = (await buildPackage(chain, [2, 4])) as any;
    wrongProof.log.proofs[0].hashes = wrongProof.log.proofs[1].hashes;
    expect((await verifyPackage(wrongProof, PINNED)).problems).toContain(
      "sequence 2 is not proven to be in the log checkpoint",
    );
    const wrongStatement = (await buildPackage(chain, [2, 4])) as any;
    wrongStatement.log.proofs[0].statement = wrongStatement.log.proofs[1].statement;
    expect((await verifyPackage(wrongStatement, PINNED)).problems).toContain(
      "sequence 2 has a statement for different evidence",
    );
    const noLog = await verifyPackage(await buildPackage(chain, [1], { log: false }), PINNED);
    expect(noLog.valid).toBe(true);
    expect(noLog.logTreeSize).toBeNull();
  });

  it("reports unknown formats and empty packages", async () => {
    const empty = (await buildPackage(chain, [])) as any;
    expect((await verifyPackage(empty, PINNED)).problems).toContain("the package discloses no evidence");
    empty.format = "other";
    expect((await verifyPackage(empty, PINNED)).problems).toEqual(["unknown package format other"]);
  });
});

describe("revoked keys", async () => {
  const chain = await entries(4);
  const next = await PrivateKey.generate();
  const witnesses = [(await witnessNoteKey()).vkey];

  async function keyList(compromisedAt: string, revoker: PrivateKey = next) {
    const rotation = await KeyRotation.issue(next, LEDGER_KEY.keyId, LEDGER_KEY, "2025-10-11T00:00:00Z");
    const revocation = await KeyRevocation.issue(
      LEDGER_KEY.keyId,
      compromisedAt,
      "2025-10-11T00:00:00Z",
      "key leaked",
      revoker,
    );
    const revoked = keyRecord(LEDGER_KEY, "REVOKED");
    revoked.revocation = {
      format: "nexusphere-ledger/key-revocation/v1",
      compromisedAt: revocation.compromisedAt,
      revokedAt: revocation.revokedAt,
      reason: revocation.reason,
      revokerKeyId: revocation.revokerKeyId,
      signature: revocation.signature,
    };
    return [revoked, keyRecord(next, "ACTIVE", rotation)];
  }

  async function verify(compromisedAt: string, witness = true, keys = witnesses, revoker?: PrivateKey) {
    return verifyPackage(await buildPackage(chain, [2], { witness }), next.publicKey.encoded, {
      witnesses: keys,
      requiredWitnesses: 0,
      keys: await keyList(compromisedAt, revoker),
    });
  }

  it("stays valid where witnesses cosigned before the compromise", async () => {
    const report = await verify("2025-10-10T00:00:00Z");
    expect(report.problems).toEqual([]);
    expect(report.revokedKeys).toEqual([LEDGER_KEY.keyId]);
  });

  it("is invalid where the witnesses cosigned after the compromise", async () => {
    const report = await verify("2025-10-01T00:00:00Z");
    expect(report.valid).toBe(false);
    expect(report.problems).toContain(
      `key ${LEDGER_KEY.keyId} was revoked as compromised from 2025-10-01T00:00:00Z, and 0 of the 1 required witness cosignatures prove that the log checkpoint was made before then`,
    );
  });

  it("needs a witness", async () => {
    expect((await verify("2025-10-10T00:00:00Z", false)).valid).toBe(false);
    expect((await verify("2025-10-10T00:00:00Z", true, [])).valid).toBe(false);
  });

  it("ignores a revocation by an untrusted key", async () => {
    const report = await verify("2025-10-01T00:00:00Z", true, witnesses, await PrivateKey.generate());
    expect(report.problems).toEqual([]);
    expect(report.revokedKeys).toEqual([]);
  });

  it("does not reach a key endorsed after the compromise", async () => {
    const keys = await keyList("2025-10-01T00:00:00Z");
    const report = await verifyPackage(await buildPackage(chain, [2], { key: next, keys: [keys[0]] }), PINNED, {
      requiredWitnesses: 0,
      keys,
    });
    expect(report.valid).toBe(false);
    expect(report.problems.some((problem) => problem.includes("does not reach"))).toBe(true);
  });

  it("refuses a key that revokes itself", async () => {
    await expect(
      KeyRevocation.issue(LEDGER_KEY.keyId, "2025-10-01T00:00:00Z", "2025-10-01T00:00:00Z", "x", LEDGER_KEY),
    ).rejects.toThrow("cannot revoke itself");
  });
});
