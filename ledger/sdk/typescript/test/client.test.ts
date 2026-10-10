import { describe, expect, it } from "vitest";
import { LedgerClient, LedgerError } from "../src/index.js";
import { zip } from "./support.js";

const URL = "http://ledger.example";

function fakeLedger() {
  const calls: { method: string; url: string; headers: Record<string, string>; body: unknown }[] = [];
  const fetcher = async (url: string | URL | Request, init: RequestInit = {}) => {
    const body = init.body === undefined ? undefined : JSON.parse(init.body as string);
    calls.push({ method: init.method ?? "GET", url: String(url), headers: init.headers as Record<string, string>, body });
    const path = String(url).substring(URL.length);
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
