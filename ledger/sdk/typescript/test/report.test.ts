import { describe, expect, it } from "vitest";
import { Json, PrivateKey, verifyReport } from "../src/index.js";
import { LEDGER_KEY, buildPackage, buildReport, entries, keyRecord, signReport } from "./support.js";

const PINNED = LEDGER_KEY.publicKey.encoded;
const SCOPE = { agentId: "sales-agent", principalId: null, fromSequence: 1, toSequence: 4 };

describe("verifyReport", async () => {
  const pkg = await buildPackage(await entries(4), [2], { scope: SCOPE });

  it("accepts a report signed by the ledger for the package", async () => {
    const check = await verifyReport(await buildReport(pkg), pkg, { publicKey: PINNED });
    expect(check.problems).toEqual([]);
    expect(check).toMatchObject({
      valid: true,
      report: "dispute",
      title: "Dispute package",
      keyId: LEDGER_KEY.keyId,
      checkpointSequence: 4,
      entries: 1,
      statuses: { SHOWN: 1 },
    });
  });

  it("breaks the signature when a finding changes", async () => {
    const report = await buildReport(pkg);
    ((report.sections as Json[])[0] as Json).status = "NOT_SHOWN";
    expect((await verifyReport(report, pkg, { publicKey: PINNED })).problems).toEqual([
      "the signature of the report is not valid",
    ]);
  });

  it("rejects a report for other entries", async () => {
    const report = await buildReport(pkg);
    const other = await buildPackage(await entries(4, ["sales-agent", "invoice-agent"]), [3], { scope: SCOPE });
    const problems = (await verifyReport(report, other, { publicKey: PINNED })).problems;
    expect(problems).toContain("the report covers other entries than the package discloses");
    expect(problems).toContain("the report is for another checkpoint than the package");
  });

  it("rejects a report signed by another key", async () => {
    const report = await buildReport(pkg, await PrivateKey.generate());
    const problems = (await verifyReport(report, pkg, { publicKey: PINNED })).problems;
    expect(problems.length).toBe(1);
    expect(problems[0]).toContain("which the package does not trust");
  });

  it("rejects an unknown status", async () => {
    const report = await buildReport(pkg);
    ((report.sections as Json[])[0] as Json).status = "COMPLIANT";
    expect((await verifyReport(await signReport(report), pkg, { publicKey: PINNED })).problems).toEqual([
      "section timeline has an unknown status COMPLIANT",
    ]);
  });

  it("checks the format, the scope, revoked keys and the values a report may hold", async () => {
    const report = await buildReport(pkg);
    expect((await verifyReport({ ...report, format: "other" }, pkg)).problems).toEqual(["unknown report format other"]);
    expect((await verifyReport({ ...report, ratio: 0.5 }, pkg)).problems).toEqual([
      "the report cannot be read: reports hold only strings, integers, booleans, arrays and objects",
    ]);
    const scope = { ...(report.scope as Json), principalId: "acme", toSequence: 3 };
    const scoped = await signReport({ ...report, scope });
    expect((await verifyReport(scoped, pkg)).problems).toEqual([
      "the report has another principalId than the package",
      "the report has another toSequence than the package",
    ]);
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
    expect((await verifyReport(report, pkg, { keys: [revoked] })).problems).toEqual([
      `the report is signed with revoked key ${LEDGER_KEY.keyId}`,
    ]);
  });
});
