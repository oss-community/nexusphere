import { describe, expect, it } from "vitest";
import {
  Exchange,
  ExchangeStatement,
  INBOUND,
  Jws,
  MISMATCH,
  NOT_RECEIVED,
  NOT_SENT,
  OUTBOUND,
  PrivateKey,
  STATEMENT_TYPE,
  StaticKeys,
  checkedStatement,
  exchangeStatement,
  parseStatement,
  reconcile,
  signStatement,
  statementPayload,
  verifyStatement,
} from "../src/index.js";

const OURS = "https://ledger.example";
const THEIRS = "https://supplier.example";
const FROM = 1790841600;
const TO = FROM + 86400;
const MANDATE = "00000000-0000-4000-8000-0000000000aa";

function exchange(direction: "OUTBOUND" | "INBOUND", n: number, changes: Partial<Exchange> = {}): Exchange {
  return {
    direction,
    requestId: "00000000-0000-4000-8000-" + String(n).padStart(12, "0"),
    requestedAt: FROM + 10,
    method: "message/send",
    mandateId: MANDATE,
    requestHash: "aa".repeat(32),
    responseHash: "bb".repeat(32),
    status: 200,
    outcome: "SUCCEEDED",
    receiptHash: "cc".repeat(32),
    ...changes,
  };
}

function statement(issuer: string, peer: string, exchanges: Exchange[], window: { from?: number; to?: number } = {})
  : ExchangeStatement {
  const to = window.to ?? TO;
  return checkedStatement({ issuer, peer, from: window.from ?? FROM, to, issuedAt: to + 60, exchanges });
}

const ours = (exchanges: Exchange[] = [], window = {}) => statement(OURS, THEIRS, exchanges, window);
const theirs = (exchanges: Exchange[] = [], window = {}) => statement(THEIRS, OURS, exchanges, window);

describe("exchange statements", () => {
  it("signs, sorts and verifies a statement", async () => {
    const key = await PrivateKey.generate();
    const signed = ours([exchange(OUTBOUND, 2, { requestedAt: FROM + 20 }),
      exchange(INBOUND, 1, { requestedAt: FROM + 20 }), exchange(OUTBOUND, 3)]);
    const token = await signStatement(signed, key.keyId, key);
    const verified = await verifyStatement(token, StaticKeys.fixed(OURS, key.publicKey));
    expect(verified).toEqual(signed);
    expect(parseStatement(token)).toEqual(signed);
    expect(exchangeStatement(statementPayload(signed))).toEqual(signed);
    expect(verified.exchanges.map((e) => e.requestId.substring(24))).toEqual(["000000000003", "000000000001",
      "000000000002"]);
    const other = await PrivateKey.generate();
    await expect(verifyStatement(token, StaticKeys.fixed(OURS, other.publicKey))).rejects.toThrow("unknown key");
    await expect(verifyStatement(token, StaticKeys.fixed(THEIRS, key.publicKey), THEIRS)).rejects
      .toThrow("The token was not issued by " + THEIRS);
    const [header, payload, signature] = token.split(".");
    const forged = `${header}.${payload}.${signature[0] === "A" ? "B" : "A"}${signature.substring(1)}`;
    await expect(verifyStatement(forged, StaticKeys.fixed(OURS, key.publicKey))).rejects.toThrow("does not match");
    const untyped = await Jws.signTyped("JWT", key.keyId, statementPayload(signed), key);
    await expect(verifyStatement(untyped, StaticKeys.fixed(OURS, key.publicKey))).rejects
      .toThrow("The token is not an EdDSA " + STATEMENT_TYPE);
  });

  it("refuses malformed statements", () => {
    expect(() => ours([], { from: TO, to: TO })).toThrow("The statement window must end after it starts");
    expect(() => ours([exchange("SIDEWAYS" as "INBOUND", 1)])).toThrow("OUTBOUND or INBOUND");
    expect(() => ours([exchange(OUTBOUND, 1, { responseHash: "xyz" })])).toThrow("malformed hash");
    expect(() => exchangeStatement({ iss: OURS, peer: THEIRS, iat: TO, from: FROM, to: TO })).toThrow("no exchanges");
  });
});

describe("reconcile", () => {
  it("matches exchanges both ways", () => {
    const r = reconcile(ours([exchange(OUTBOUND, 1), exchange(INBOUND, 2)]),
      theirs([exchange(INBOUND, 1), exchange(OUTBOUND, 2)]));
    expect(r.reconciled).toBe(true);
    expect(JSON.parse(JSON.stringify(r))).toEqual({
      ours: OURS, theirs: THEIRS, from: "2026-10-01T08:00:00Z", to: "2026-10-02T08:00:00Z", matched: 2,
      undelivered: 0, discrepancies: [],
    });
  });

  it("tells undelivered exchanges from exchanges never received", () => {
    const r = reconcile(ours([
      exchange(OUTBOUND, 1),
      exchange(OUTBOUND, 2, { status: 401, receiptHash: null }),
      exchange(OUTBOUND, 3, { status: 503 }),
      exchange(OUTBOUND, 4, { status: null, receiptHash: null }),
    ]), theirs());
    expect(r.undelivered).toBe(1);
    expect(r.discrepancies.map((d) => [d.requestId.substring(24), d.kind, d.sender])).toEqual([
      ["000000000001", NOT_RECEIVED, OURS], ["000000000003", NOT_RECEIVED, OURS], ["000000000004", NOT_RECEIVED, OURS],
    ]);
  });

  it("finds exchanges never sent", () => {
    const r = reconcile(ours([exchange(INBOUND, 1, { requestedAt: FROM + 30 })]),
      theirs([exchange(INBOUND, 2, { requestedAt: FROM + 20 })]));
    expect(r.reconciled).toBe(false);
    expect(JSON.parse(JSON.stringify(r)).discrepancies).toEqual([
      { requestId: "00000000-0000-4000-8000-000000000002", kind: NOT_SENT, sender: OURS,
        requestedAt: "2026-10-01T08:00:20Z", fields: [] },
      { requestId: "00000000-0000-4000-8000-000000000001", kind: NOT_SENT, sender: THEIRS,
        requestedAt: "2026-10-01T08:00:30Z", fields: [] },
    ]);
  });

  it("names the fields that differ", () => {
    const r = reconcile(ours([
      exchange(OUTBOUND, 1), exchange(OUTBOUND, 2), exchange(OUTBOUND, 3),
      exchange(OUTBOUND, 4, { status: null, responseHash: null, receiptHash: null }),
    ]), theirs([
      exchange(INBOUND, 1, { requestedAt: FROM + 11, method: "tasks/get", mandateId: MANDATE.slice(0, -2) + "bb",
        requestHash: "dd".repeat(32), status: 500, responseHash: "ee".repeat(32), receiptHash: "ff".repeat(32),
        outcome: "FAILED" }),
      exchange(INBOUND, 2, { outcome: "FAILED" }),
      exchange(INBOUND, 3, { outcome: "FAILED", receiptHash: null }),
      exchange(INBOUND, 4, { status: 500, responseHash: "ee".repeat(32) }),
    ]));
    expect(r.matched).toBe(2);
    expect(r.discrepancies.map((d) => d.kind)).toEqual([MISMATCH, MISMATCH]);
    expect(r.discrepancies[0].fields).toEqual(["requestedAt", "method", "mandateId", "requestHash", "status",
      "responseHash", "receiptHash"]);
    expect(r.discrepancies[1].fields).toEqual(["outcome"]);
  });

  it("orders request IDs as UUIDs", () => {
    const r = reconcile(ours([exchange(OUTBOUND, 1)]),
      theirs([exchange(INBOUND, 2, { requestId: "80000000-0000-4000-8000-000000000002" })]));
    expect(r.discrepancies.map((d) => d.requestId)).toEqual(["80000000-0000-4000-8000-000000000002",
      "00000000-0000-4000-8000-000000000001"]);
  });

  it("refuses statements about others", () => {
    expect(() => reconcile(ours(), statement(THEIRS, "https://other.example", []))).toThrow(
      "The statements are not about each other: https://ledger.example names https://supplier.example and "
        + "https://supplier.example names https://other.example");
  });

  it("refuses statements over different windows", () => {
    expect(() => reconcile(ours(), theirs([], { to: TO + 1 }))).toThrow("The statements cover different windows");
  });
});
