import { describe, expect, it } from "vitest";
import {
  Action,
  Denied,
  EvidenceLink,
  EvidenceStatement,
  LedgerClient,
  LedgerError,
  LogReceipt,
  MandateVerifier,
  Note,
  NoteKey,
  PublicKey,
  SdJwt,
  verifyPackage,
} from "../src/index.js";

const URL = process.env.NEXUSPHERE_LEDGER_URL;
const OPERATOR_KEY = process.env.NEXUSPHERE_LEDGER_API_KEY ?? "nexusphere-ledger-development-key-change-me";

describe.skipIf(!URL)("a running ledger", async () => {
  const operator = new LedgerClient(URL!, { apiKey: OPERATOR_KEY });
  const agentId = "ts-agent-" + crypto.randomUUID().substring(0, 8);
  const registered = URL ? await operator.registerAgent(agentId, "TypeScript SDK test", "acme") : {};
  const agent = new LedgerClient(URL ?? "", { apiKey: registered.apiKey as string });
  const grant = URL
    ? await operator.createGrant({
        principalId: "alice",
        agentId,
        actions: ["tools/call"],
        targets: ["read_*"],
        expiresAt: new Date(Date.now() + 86_400_000),
        maxUses: 10,
      })
    : {};

  it("records, decides, exports and verifies", async () => {
    const single = await agent.record({
      agentId,
      principalId: "alice",
      action: "tools/call",
      outcome: "SUCCEEDED",
      target: "read_invoice",
      decision: "ALLOW",
      input: { invoice: 7 },
      attributes: { sdk: "typescript" },
    });
    expect((single.hash as string).length).toBe(64);
    const batch = await agent.recordBatch([
      { agentId, principalId: "alice", action: "tools/call", outcome: "SUCCEEDED" },
      { agentId, principalId: "alice", action: "tools/call", outcome: "FAILED" },
    ]);
    expect(batch[1].sequence).toBe((batch[0].sequence as number) + 1);
    let handle: Action | null = null;
    const text = await agent.act({ principalId: "alice", action: "tools/call", target: "read_invoice", input: "invoice 7" },
      async (action) => {
        handle = action;
        await action.output("contents");
        return "contents";
      });
    expect(text).toBe("contents");
    expect(handle!.reported!.outcome).toBe("SUCCEEDED");
    const denied = await agent
      .act({ principalId: "alice", action: "tools/call", target: "send_email" }, () => {
        throw new Error("a denied action must not run");
      })
      .catch((e) => e);
    expect(denied).toBeInstanceOf(Denied);
    expect(denied.decision.reasonCode).toBe("NOT_COVERED");
    await expect(
      agent.act({ principalId: "alice", action: "tools/call", target: "read_mail" }, () => {
        throw new TypeError("tool failed");
      }),
    ).rejects.toThrow("tool failed");
    await operator.createCheckpoint();
    const pinned = await operator.activePublicKey();
    const pkg = await operator.exportPackage({ agentId });
    const report = await verifyPackage(pkg, pinned);
    expect(report.problems).toEqual([]);
    expect(report.disclosedEntries).toBeGreaterThanOrEqual(7);
    expect(report.provenEntries).toBe(report.disclosedEntries);
    expect(report.receiptedEntries).toBe(report.disclosedEntries);
    const changed = JSON.parse(JSON.stringify(pkg));
    changed.links.find((link: any) => link.entry).entry.outcome = "FAILED";
    expect((await verifyPackage(changed, pinned)).valid).toBe(false);
    const key = await PublicKey.fromBase64(pinned);
    const statement = EvidenceStatement.parse(await agent.statement(single.id as string));
    const receipt = LogReceipt.parse(await agent.receipt(single.id as string));
    expect(await statement.verify(key)).toBe(true);
    expect(statement.describes(await EvidenceLink.ofEntry(single))).toBe(true);
    expect(await receipt.verify(await statement.leafHash(), key)).toBe(true);
    const note = Note.parse(await agent.logCheckpoint());
    expect(await note.signedBy(await NoteKey.parse(await agent.logKey()))).toBe(true);
    expect(await agent.evidence(single.id as string)).toEqual(single);
    const ids: unknown[] = [];
    for await (const entry of agent.iterEvidence({ limit: 2 })) {
      ids.push(entry.id);
    }
    expect(ids).toContain(single.id);
  });

  it("verifies a mandate from the ledger and sees its revocation", async () => {
    const issued = await operator.issueMandate(grant.id as string, "https://supplier.example");
    const token = issued.token as string;
    const issuer = SdJwt.parse(token).jwt.payload.iss as string;
    const check = await new MandateVerifier(issuer, { audience: "https://supplier.example" }).verifyAction(
      token,
      "tools/call",
      "read_invoice",
    );
    expect(check.problems).toEqual([]);
    expect(check.claims!.principalId).toBe("alice");
    await operator.revokeMandate(issued.id as string, "test");
    expect((await new MandateVerifier(issuer).verify(token)).has("REVOKED")).toBe(true);
  });

  it("raises ledger errors", async () => {
    const invalid = await agent.record({ agentId, action: "tools/call", outcome: "SUCCEEDED" }).catch((e) => e);
    expect(invalid).toBeInstanceOf(LedgerError);
    expect(invalid).toMatchObject({ status: 400, code: "INVALID_REQUEST" });
    const forbidden = await agent.exportPackage().catch((e) => e);
    expect(forbidden).toMatchObject({ status: 403 });
  });
});
