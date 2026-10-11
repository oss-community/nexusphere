import { Json, isSha256 } from "./evidence.js";
import { PrivateKey } from "./keys.js";
import { JWS_ALGORITHM, Jws, KeyResolver } from "./mandate.js";
import { epochText } from "./timestamps.js";

export const STATEMENT_TYPE = "nexusphere-a2a-statement+jwt";
export const OUTBOUND = "OUTBOUND";
export const INBOUND = "INBOUND";
export const MAX_EXCHANGES = 10_000;
export const NOT_RECEIVED = "NOT_RECEIVED";
export const NOT_SENT = "NOT_SENT";
export const MISMATCH = "MISMATCH";

const UNDELIVERED = new Set([401, 502, 503, 504]);
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export type ExchangeDirection = typeof OUTBOUND | typeof INBOUND;
export type DiscrepancyKind = typeof NOT_RECEIVED | typeof NOT_SENT | typeof MISMATCH;

export interface Exchange {
  direction: ExchangeDirection;
  requestId: string;
  requestedAt: number;
  method: string;
  mandateId: string;
  requestHash: string;
  responseHash: string | null;
  status: number | null;
  outcome: string | null;
  receiptHash: string | null;
}

export interface ExchangeStatement {
  issuer: string;
  peer: string;
  from: number;
  to: number;
  issuedAt: number;
  exchanges: Exchange[];
}

export interface Discrepancy {
  requestId: string;
  kind: DiscrepancyKind;
  sender: string;
  requestedAt: number;
  fields: string[];
}

export class Reconciliation {
  constructor(
    readonly ours: string,
    readonly theirs: string,
    readonly from: number,
    readonly to: number,
    readonly matched: number,
    readonly undelivered: number,
    readonly discrepancies: Discrepancy[],
  ) {}

  get reconciled(): boolean {
    return this.discrepancies.length === 0;
  }

  toJSON(): Json {
    return {
      ours: this.ours,
      theirs: this.theirs,
      from: epochText(this.from),
      to: epochText(this.to),
      matched: this.matched,
      undelivered: this.undelivered,
      discrepancies: this.discrepancies.map((d) => ({
        requestId: d.requestId,
        kind: d.kind,
        sender: d.sender,
        requestedAt: epochText(d.requestedAt),
        fields: [...d.fields],
      })),
    };
  }
}

export function checkedExchange(e: Exchange): Exchange {
  if (e.direction !== OUTBOUND && e.direction !== INBOUND) {
    throw new Error("The exchange direction must be OUTBOUND or INBOUND");
  }
  const requestId = uuid(e.requestId);
  const mandateId = uuid(e.mandateId);
  if (!isSha256(e.requestHash) || (e.responseHash !== null && !isSha256(e.responseHash))
    || (e.receiptHash !== null && !isSha256(e.receiptHash))) {
    throw new Error(`The exchange ${requestId} has a malformed hash`);
  }
  return { ...e, requestId, mandateId };
}

export function checkedStatement(s: ExchangeStatement): ExchangeStatement {
  if (s.exchanges.length > MAX_EXCHANGES) {
    throw new Error(`A statement holds at most ${MAX_EXCHANGES} exchanges`);
  }
  if (!(s.from < s.to)) {
    throw new Error("The statement window must end after it starts");
  }
  const exchanges = s.exchanges.map(checkedExchange)
    .sort((a, b) => a.requestedAt - b.requestedAt || compareUuids(a.requestId, b.requestId));
  return { ...s, exchanges };
}

export function exchangeOf(n: unknown): Exchange {
  if (!isObject(n)) {
    throw new Error("The token has a malformed exchange");
  }
  return checkedExchange({
    direction: text(n, "direction") as ExchangeDirection,
    requestId: text(n, "requestId"),
    requestedAt: number(n, "requestedAt"),
    method: text(n, "method"),
    mandateId: text(n, "mandateId"),
    requestHash: text(n, "requestHash"),
    responseHash: optional(n, "responseHash"),
    status: Number.isInteger(n.status) ? (n.status as number) : null,
    outcome: optional(n, "outcome"),
    receiptHash: optional(n, "receiptHash"),
  });
}

export function exchangeStatement(p: Json): ExchangeStatement {
  if (!Array.isArray(p.exchanges)) {
    throw new Error("The token has no exchanges");
  }
  const exchanges = p.exchanges.map(exchangeOf);
  return checkedStatement({
    issuer: text(p, "iss"),
    peer: text(p, "peer"),
    from: number(p, "from"),
    to: number(p, "to"),
    issuedAt: number(p, "iat"),
    exchanges,
  });
}

export function statementPayload(s: ExchangeStatement): Json {
  return {
    iss: s.issuer,
    peer: s.peer,
    iat: s.issuedAt,
    from: s.from,
    to: s.to,
    exchanges: s.exchanges.map((e) => ({
      direction: e.direction,
      requestId: e.requestId,
      requestedAt: e.requestedAt,
      method: e.method,
      mandateId: e.mandateId,
      requestHash: e.requestHash,
      responseHash: e.responseHash,
      status: e.status,
      outcome: e.outcome,
      receiptHash: e.receiptHash,
    })),
  };
}

export function signStatement(s: ExchangeStatement, keyId: string, key: PrivateKey): Promise<string> {
  return Jws.signTyped(STATEMENT_TYPE, keyId, statementPayload(checkedStatement(s)), key);
}

export function parseStatement(token: string): ExchangeStatement {
  return exchangeStatement(Jws.parse(token).payload);
}

export async function verifyStatement(token: string, keys: KeyResolver, issuer?: string): Promise<ExchangeStatement> {
  const parsed = Jws.parse(token);
  const expected = issuer ?? (typeof parsed.payload.iss === "string" ? parsed.payload.iss : "");
  if (parsed.header.typ !== STATEMENT_TYPE || parsed.header.alg !== JWS_ALGORITHM) {
    throw new Error("The token is not an EdDSA " + STATEMENT_TYPE);
  }
  if (parsed.payload.iss !== expected) {
    throw new Error("The token was not issued by " + expected);
  }
  const key = await keys.resolve(expected, typeof parsed.header.kid === "string" ? parsed.header.kid : "");
  if (key === null) {
    throw new Error("The token is signed with an unknown key");
  }
  if (!(await parsed.verify(key))) {
    throw new Error("The token signature does not match the issuer's key");
  }
  return exchangeStatement(parsed.payload);
}

export function reconcile(ours: ExchangeStatement, theirs: ExchangeStatement): Reconciliation {
  if (ours.peer !== theirs.issuer || theirs.peer !== ours.issuer) {
    throw new Error(`The statements are not about each other: ${ours.issuer} names ${ours.peer} and ${
      theirs.issuer} names ${theirs.peer}`);
  }
  if (ours.from !== theirs.from || ours.to !== theirs.to) {
    throw new Error("The statements cover different windows");
  }
  const counts = [0, 0];
  const discrepancies: Discrepancy[] = [];
  compare(ours, theirs, counts, discrepancies);
  compare(theirs, ours, counts, discrepancies);
  discrepancies.sort((a, b) => a.requestedAt - b.requestedAt || compareUuids(a.requestId, b.requestId));
  return new Reconciliation(ours.issuer, theirs.issuer, ours.from, ours.to, counts[0], counts[1], discrepancies);
}

function compare(sender: ExchangeStatement, receiver: ExchangeStatement, counts: number[],
  discrepancies: Discrepancy[]) {
  const received = new Map<string, Exchange>();
  receiver.exchanges.filter((e) => e.direction === INBOUND).forEach((e) => received.set(e.requestId, e));
  const seen = new Set<string>();
  for (const sent of sender.exchanges) {
    if (sent.direction !== OUTBOUND) {
      continue;
    }
    seen.add(sent.requestId);
    const inbound = received.get(sent.requestId);
    if (inbound === undefined) {
      if (sent.receiptHash === null && sent.status !== null && UNDELIVERED.has(sent.status)) {
        counts[1]++;
      } else {
        discrepancies.push({
          requestId: sent.requestId, kind: NOT_RECEIVED, sender: sender.issuer, requestedAt: sent.requestedAt,
          fields: [],
        });
      }
      continue;
    }
    const differing = fields(sent, inbound);
    if (differing.length === 0) {
      counts[0]++;
    } else {
      discrepancies.push({
        requestId: sent.requestId, kind: MISMATCH, sender: sender.issuer, requestedAt: sent.requestedAt,
        fields: differing,
      });
    }
  }
  for (const e of received.values()) {
    if (!seen.has(e.requestId)) {
      discrepancies.push({
        requestId: e.requestId, kind: NOT_SENT, sender: sender.issuer, requestedAt: e.requestedAt, fields: [],
      });
    }
  }
}

function fields(sent: Exchange, inbound: Exchange): string[] {
  const differing: string[] = [];
  if (sent.requestedAt !== inbound.requestedAt) {
    differing.push("requestedAt");
  }
  if (sent.method !== inbound.method) {
    differing.push("method");
  }
  if (sent.mandateId !== inbound.mandateId) {
    differing.push("mandateId");
  }
  if (sent.requestHash !== inbound.requestHash) {
    differing.push("requestHash");
  }
  if (sent.status !== null && inbound.status !== null && sent.status !== inbound.status) {
    differing.push("status");
  }
  if (sent.responseHash !== null && inbound.responseHash !== null && sent.responseHash !== inbound.responseHash) {
    differing.push("responseHash");
  }
  if (sent.receiptHash !== null && inbound.receiptHash !== null) {
    if (sent.receiptHash !== inbound.receiptHash) {
      differing.push("receiptHash");
    } else if (sent.outcome !== inbound.outcome) {
      differing.push("outcome");
    }
  }
  return differing;
}

function compareUuids(a: string, b: string): number {
  const x = a.replace(/-/g, "");
  const y = b.replace(/-/g, "");
  const high = signed(x.substring(0, 16)) - signed(y.substring(0, 16));
  const difference = high !== 0n ? high : signed(x.substring(16)) - signed(y.substring(16));
  return difference < 0n ? -1 : difference > 0n ? 1 : 0;
}

function signed(hex: string): bigint {
  return BigInt.asIntN(64, BigInt("0x" + hex));
}

function isObject(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function text(node: Json, name: string): string {
  if (typeof node[name] !== "string") {
    throw new Error("The token has no " + name);
  }
  return node[name] as string;
}

function optional(node: Json, name: string): string | null {
  return typeof node[name] === "string" ? (node[name] as string) : null;
}

function number(node: Json, name: string): number {
  if (typeof node[name] !== "number") {
    throw new Error("The token has no " + name);
  }
  return Math.trunc(node[name] as number);
}

function uuid(value: string): string {
  if (typeof value !== "string" || !UUID.test(value)) {
    throw new Error("Invalid UUID string: " + value);
  }
  return value.toLowerCase();
}
