import { describe, expect, it } from "vitest";
import { Denied, LedgerClient, LedgerError } from "../src/index.js";
import { zip } from "./support.js";

const URL = "http://ledger.example";

const APPROVAL = {
  id: "00000000-0000-4000-8000-0000000000aa",
  agentId: "invoice-agent",
  principalId: "acme",
  action: "tools/call",
  target: "pay_invoice",
  policies: ["policy1"],
  approver: "operator",
  status: "PENDING",
  expiresAt: "2026-10-01T09:00:00Z",
  method: null,
  used: false,
};

const ANOMALY = {
  id: "00000000-0000-4000-8000-0000000000cc",
  agentId: "invoice-agent",
  kind: "DENIALS",
  status: "OPEN",
  observed: 0.5,
  expected: 0.05,
  score: 4.2,
  windowStart: "2026-10-01T07:00:00Z",
  windowEnd: "2026-10-01T08:00:00Z",
  detectedAt: "2026-10-01T08:00:01Z",
  resolvedAt: null,
};

function fakeLedger() {
  const calls: { method: string; url: string; headers: Record<string, string>; body: unknown }[] = [];
  const fetcher = async (url: string | URL | Request, init: RequestInit = {}) => {
    const body = init.body === undefined ? undefined : JSON.parse(init.body as string);
    calls.push({ method: init.method ?? "GET", url: String(url), headers: init.headers as Record<string, string>, body });
    const path = String(url).substring(URL.length);
    if (path === "/api/v1/prompts" && init.method === "PUT") {
      const text = (body as { text: string }).text;
      return Response.json({ hash: "ab".repeat(32) }, { status: text === "new" ? 201 : 200 });
    }
    if (path === "/api/v1/prompts/" + "ab".repeat(32)) {
      return Response.json({ hash: "ab".repeat(32), text: "new", createdAt: "2026-10-01T08:00:00Z", createdBy: "operator" });
    }
    if (path === "/api/v1/policies" || path === "/api/v1/policies/" + "ab".repeat(32)) {
      const activatedAt = path === "/api/v1/policies" ? "2026-10-01T08:00:00Z" : null;
      return Response.json({ hash: "ab".repeat(32), text: "permit(principal, action, resource);", policies: ["policy0"],
        defaultEffect: "deny", createdAt: "2026-10-01T08:00:00Z", activatedAt });
    }
    if (path === "/api/v1/decisions" && (body as { target?: string }).target === "pay_invoice") {
      return Response.json({ decisionId: "00000000-0000-4000-8000-0000000000bb", decision: "DENY",
        reasonCode: "APPROVAL_PENDING", reason: "The operator must approve.", approvalId: APPROVAL.id });
    }
    if (path.startsWith("/api/v1/approvals?")) {
      return Response.json({ items: [APPROVAL] });
    }
    if (path === "/api/v1/approvals/" + APPROVAL.id) {
      return Response.json(APPROVAL);
    }
    if (path === `/api/v1/approvals/${APPROVAL.id}/approve`) {
      return Response.json({ ...APPROVAL, status: "APPROVED", method: "operator" });
    }
    if (path === `/api/v1/approvals/${APPROVAL.id}/deny`) {
      return Response.json({ code: "APPROVAL_RESOLVED", message: "The approval is resolved." }, { status: 409 });
    }
    if (path.startsWith("/api/v1/anomalies?") || path === "/api/v1/anomalies/detect") {
      return Response.json({ items: [ANOMALY] });
    }
    if (path === "/api/v1/anomalies/" + ANOMALY.id) {
      return Response.json(ANOMALY);
    }
    if (path === "/api/v1/evidence" || path === "/api/v1/decisions" || path === "/api/v1/packages") {
      return Response.json({ ok: true });
    }
    if (path === "/api/v1/index/proofs") {
      return Response.json({ format: "nexusphere-ledger/index-proof/v1", term: (body as { term: unknown }).term });
    }
    if (path === "/api/v1/agents/invoice-agent/track-record") {
      return Response.json({ credential: "token~", id: "00000000-0000-4000-8000-0000000000dd", checkpoint: 3 });
    }
    if (path === "/api/v1/a2a/statements") {
      return Response.json({ statement: "a.b.c", issuer: "https://ledger.example", peer: "https://supplier.example",
        exchanges: 2 });
    }
    if (path === "/api/v1/a2a/reconciliations") {
      return Response.json({ reconciled: true, statement: "d.e.f", evidenceSequence: 9 });
    }
    if (path === "/api/v1/reports") {
      return Response.json([{ id: "dispute", title: "Dispute package", profiles: ["baseline"] }]);
    }
    if (path === "/api/v1/reports/dispute") {
      return Response.json({ format: "nexusphere-ledger/report/v1", report: "dispute" });
    }
    if (path === "/api/v1/reports/dispute?format=html") {
      return new Response("<!doctype html><title>Dispute package</title>", {
        headers: { "Content-Type": "text/html;charset=UTF-8" },
      });
    }
    if (path === "/api/v1/disputes" && body && (body as { title?: string }).title) {
      return new Response(zip({ "report.json": "{}" }), { headers: { "Content-Type": "application/zip" } });
    }
    return Response.json({ code: "INVALID_REQUEST", message: "The dispute request has invalid fields.",
      details: { title: "is required, at most 200 characters" } }, { status: 400 });
  };
  return { fetcher: fetcher as typeof fetch, calls };
}

describe("LedgerClient reports", () => {
  it("lists the offered reports", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL + "/", { apiKey: "operator", fetch: ledger.fetcher });
    expect(await client.reports()).toEqual([{ id: "dispute", title: "Dispute package", profiles: ["baseline"] }]);
    expect(ledger.calls[0]).toMatchObject({ method: "GET", url: URL + "/api/v1/reports", body: undefined });
    expect(ledger.calls[0].headers.Authorization).toBe("Bearer operator");
  });

  it("creates a report as JSON and as HTML", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL, { fetch: ledger.fetcher });
    expect((await client.createReport("dispute", { agentId: "invoice-agent", toSequence: 7 })).report).toBe("dispute");
    expect(ledger.calls[0]).toMatchObject({
      method: "POST",
      url: URL + "/api/v1/reports/dispute",
      body: { agentId: "invoice-agent", toSequence: 7 },
    });
    expect(await client.createReportHtml("dispute")).toBe("<!doctype html><title>Dispute package</title>");
    expect(ledger.calls[1]).toMatchObject({ method: "POST", url: URL + "/api/v1/reports/dispute?format=html", body: {} });
  });

  it("creates a dispute package", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL, { fetch: ledger.fetcher });
    const archive = await client.createDispute({ title: "Invoice 4711 paid twice", principalId: "acme" });
    expect(archive).toBeInstanceOf(Uint8Array);
    expect(archive).toEqual(zip({ "report.json": "{}" }));
    expect(ledger.calls[0]).toMatchObject({
      method: "POST",
      url: URL + "/api/v1/disputes",
      body: { title: "Invoice 4711 paid twice", principalId: "acme" },
    });
    const error = await client.createDispute({ title: "" }).catch((e) => e);
    expect(error).toBeInstanceOf(LedgerError);
    expect(error).toMatchObject({ status: 400, code: "INVALID_REQUEST", details: { title: "is required, at most 200 characters" } });
  });
});

describe("LedgerClient models and prompts", () => {
  const promptHash = "ab".repeat(32);

  it("stores and reads a prompt text", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL, { fetch: ledger.fetcher });
    expect(await client.storePrompt("new")).toBe(promptHash);
    expect(await client.storePrompt("existing")).toBe(promptHash);
    expect(ledger.calls[0]).toMatchObject({ method: "PUT", url: URL + "/api/v1/prompts", body: { text: "new" } });
    expect(await client.prompt(promptHash)).toEqual({
      hash: promptHash,
      text: "new",
      createdAt: "2026-10-01T08:00:00Z",
      createdBy: "operator",
    });
    expect(ledger.calls[2]).toMatchObject({ method: "GET", url: URL + "/api/v1/prompts/" + promptHash, body: undefined });
  });

  it("sends the model of evidence and decisions", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL, { fetch: ledger.fetcher });
    await client.record({ agentId: "invoice-agent", action: "tools/call", outcome: "SUCCEEDED",
      model: { name: "model-a", version: "1", promptHash, configHash: null } });
    expect(ledger.calls[0].body).toEqual({ agentId: "invoice-agent", action: "tools/call", outcome: "SUCCEEDED",
      model: { name: "model-a", version: "1", promptHash } });
    await client.decide({ principalId: "acme", action: "tools/call", model: { name: "model-a" } });
    expect(ledger.calls[1]).toMatchObject({ url: URL + "/api/v1/decisions",
      body: { principalId: "acme", action: "tools/call", model: { name: "model-a" } } });
    await client.decide({ principalId: "acme", action: "tools/call" });
    expect(ledger.calls[2].body).not.toHaveProperty("model");
  });

  it("asks for the prompt texts in packages, reports and disputes", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL, { fetch: ledger.fetcher });
    await client.exportPackage({ agentId: "invoice-agent", includePrompts: true });
    expect(ledger.calls[0]).toMatchObject({ url: URL + "/api/v1/packages",
      body: { agentId: "invoice-agent", includePrompts: true } });
    await client.createReport("dispute", { includePrompts: true });
    expect(ledger.calls[1].body).toEqual({ includePrompts: true });
    await client.createDispute({ title: "Invoice 4711 paid twice", includePrompts: true });
    expect(ledger.calls[2].body).toEqual({ title: "Invoice 4711 paid twice", includePrompts: true });
  });
});

describe("LedgerClient policy sets", () => {
  const policyHash = "ab".repeat(32);

  it("activates and reads a policy set", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL, { fetch: ledger.fetcher });
    const active = await client.activatePolicies("permit(principal, action, resource);");
    expect(active).toEqual({ hash: policyHash, text: "permit(principal, action, resource);", policies: ["policy0"],
      defaultEffect: "deny", createdAt: "2026-10-01T08:00:00Z", activatedAt: "2026-10-01T08:00:00Z" });
    expect(ledger.calls[0]).toMatchObject({ method: "PUT", url: URL + "/api/v1/policies",
      body: { text: "permit(principal, action, resource);" } });
    expect(await client.activePolicies()).toEqual(active);
    expect(ledger.calls[1]).toMatchObject({ method: "GET", url: URL + "/api/v1/policies", body: undefined });
    expect(await client.policySet(policyHash)).toEqual({ ...active, activatedAt: null });
    expect(ledger.calls[2]).toMatchObject({ method: "GET", url: URL + "/api/v1/policies/" + policyHash });
  });
});

describe("LedgerClient index proofs", () => {
  it("asks for a proof of a term, at the latest or a given checkpoint", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL, { apiKey: "operator", fetch: ledger.fetcher });
    const term = { type: "grant", delegationId: "grant-1" };
    expect(await client.indexProof(term)).toEqual({ format: "nexusphere-ledger/index-proof/v1", term });
    expect(ledger.calls[0]).toMatchObject({ method: "POST", url: URL + "/api/v1/index/proofs", body: { term } });
    await client.indexProof({ type: "agent", agentId: "invoice-agent" }, 7);
    expect(ledger.calls[1].body).toEqual({ term: { type: "agent", agentId: "invoice-agent" }, sequence: 7 });
  });
});

describe("LedgerClient track records", () => {
  it("asks for the track record of an agent, for any or one audience", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL, { apiKey: "invoice-agent-key", fetch: ledger.fetcher });
    expect(await client.issueTrackRecord("invoice-agent")).toMatchObject({ credential: "token~", checkpoint: 3 });
    expect(ledger.calls[0]).toMatchObject({ method: "POST", url: URL + "/api/v1/agents/invoice-agent/track-record",
      body: {} });
    await client.issueTrackRecord("invoice-agent", "https://supplier.example", "P7D");
    expect(ledger.calls[1].body).toEqual({ audience: "https://supplier.example", validFor: "P7D" });
  });
});

describe("LedgerClient A2A reconciliation", () => {
  it("asks for a signed statement and reconciles the peer's", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL, { apiKey: "operator", fetch: ledger.fetcher });
    expect(await client.a2aStatement("supplier", "2026-10-01T08:00:00Z", new Date("2026-10-02T08:00:00Z")))
      .toMatchObject({ statement: "a.b.c", exchanges: 2 });
    expect(ledger.calls[0]).toMatchObject({ method: "POST", url: URL + "/api/v1/a2a/statements", body: {
      peer: "supplier", from: "2026-10-01T08:00:00.000000Z", to: "2026-10-02T08:00:00.000000Z" } });
    expect(await client.reconcileA2a("x.y.z")).toEqual({ reconciled: true, statement: "d.e.f", evidenceSequence: 9 });
    expect(ledger.calls[1]).toMatchObject({ method: "POST", url: URL + "/api/v1/a2a/reconciliations",
      body: { statement: "x.y.z" } });
  });
});

describe("LedgerClient approvals", () => {
  it("lists, reads and resolves approval requests", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL, { apiKey: "operator", fetch: ledger.fetcher });
    expect(await client.approvals()).toEqual([APPROVAL]);
    expect(ledger.calls[0]).toMatchObject({ method: "GET", url: URL + "/api/v1/approvals?limit=100", body: undefined });
    await client.approvals({ status: "PENDING", agentId: "invoice-agent", principalId: "acme", limit: 5 });
    expect(ledger.calls[1].url)
      .toBe(URL + "/api/v1/approvals?status=PENDING&agentId=invoice-agent&principalId=acme&limit=5");
    expect(await client.approval(APPROVAL.id)).toEqual(APPROVAL);
    expect(ledger.calls[2]).toMatchObject({ method: "GET", url: URL + "/api/v1/approvals/" + APPROVAL.id });
    expect((await client.approve(APPROVAL.id)).status).toBe("APPROVED");
    expect(ledger.calls[3]).toMatchObject({ method: "POST", url: `${URL}/api/v1/approvals/${APPROVAL.id}/approve`,
      body: {} });
    const error = await client.denyApproval(APPROVAL.id, "Not this month.").catch((e) => e);
    expect(error).toBeInstanceOf(LedgerError);
    expect(error).toMatchObject({ status: 409, code: "APPROVAL_RESOLVED" });
    expect(ledger.calls[4]).toMatchObject({ method: "POST", url: `${URL}/api/v1/approvals/${APPROVAL.id}/deny`,
      body: { reason: "Not this month." } });
  });

  it("throws Denied with the approval when the action waits for one", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL, { fetch: ledger.fetcher });
    let ran = false;
    const error = await client.act({ principalId: "acme", action: "tools/call", target: "pay_invoice" }, () => {
      ran = true;
    }).catch((e) => e);
    expect(error).toBeInstanceOf(Denied);
    expect(error.decision.reasonCode).toBe("APPROVAL_PENDING");
    expect(error.approvalId).toBe(APPROVAL.id);
    expect(ran).toBe(false);
    expect(ledger.calls).toHaveLength(1);
    const other = await client.act({ principalId: "acme", action: "tools/call", target: "read_invoice" }, () => 1)
      .catch((e) => e);
    expect(other).toBeInstanceOf(Denied);
    expect(other.approvalId).toBeNull();
  });
});

describe("LedgerClient anomalies", () => {
  it("lists, reads and detects anomalies", async () => {
    const ledger = fakeLedger();
    const client = new LedgerClient(URL, { apiKey: "operator", fetch: ledger.fetcher });
    expect(await client.anomalies()).toEqual([ANOMALY]);
    expect(ledger.calls[0]).toMatchObject({ method: "GET", url: URL + "/api/v1/anomalies?limit=100", body: undefined });
    await client.anomalies({ status: "RESOLVED", agentId: "invoice-agent", limit: 5 });
    expect(ledger.calls[1].url).toBe(URL + "/api/v1/anomalies?status=RESOLVED&agentId=invoice-agent&limit=5");
    expect(await client.anomaly(ANOMALY.id)).toEqual(ANOMALY);
    expect(ledger.calls[2]).toMatchObject({ method: "GET", url: URL + "/api/v1/anomalies/" + ANOMALY.id });
    expect(await client.detectAnomalies()).toEqual([ANOMALY]);
    expect(ledger.calls[3]).toMatchObject({ method: "POST", url: URL + "/api/v1/anomalies/detect", body: {} });
  });
});
