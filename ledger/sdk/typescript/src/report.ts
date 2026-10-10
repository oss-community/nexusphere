import { canonicalBytes, sha256Hex } from "./canonical.js";
import { Json } from "./evidence.js";
import { packageTrustedKeys } from "./package.js";

export const REPORT_FORMAT = "nexusphere-ledger/report/v1";
export const REPORT_STATUSES = ["SHOWN", "PARTLY_SHOWN", "NOT_SHOWN"];

export interface ReportCheck {
  valid: boolean;
  report: string | null;
  title: string | null;
  keyId: string | null;
  checkpointSequence: number | null;
  entries: number | null;
  statuses: Record<string, number>;
  problems: string[];
}

export interface ReportOptions {
  publicKey?: string | null;
  keys?: Json[] | null;
}

export function reportSignedBytes(report: unknown): Uint8Array {
  const map = object(report);
  delete map.signature;
  return canonicalBytes(map);
}

export function entriesDigest(hashes: string[]): Promise<string> {
  return sha256Hex(canonicalBytes({ hashes }));
}

export async function verifyReport(report: unknown, pkg: unknown, options: ReportOptions = {}): Promise<ReportCheck> {
  const problems: string[] = [];
  const id = text(path(report, "report"), null);
  const title = text(path(report, "title"), null);
  const keyId = text(path(report, "keyId"), null);
  const evidence = path(report, "evidence");
  const sequence = integral(path(evidence, "checkpoint"));
  const entries = integral(path(evidence, "entries"));
  const counted = new Map<string, number>();
  for (const section of elements(path(report, "sections"))) {
    const status = text(path(section, "status"), "")!;
    if (!REPORT_STATUSES.includes(status)) {
      problems.push(`section ${text(path(section, "id"), "?")} has an unknown status ${status}`);
    }
    counted.set(status, (counted.get(status) ?? 0) + 1);
  }
  const statuses = Object.fromEntries([...counted.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
  const check = (): ReportCheck => ({
    valid: problems.length === 0,
    report: id,
    title,
    keyId,
    checkpointSequence: sequence,
    entries,
    statuses,
    problems,
  });
  if (text(path(report, "format"), null) !== REPORT_FORMAT) {
    problems.push(`unknown report format ${text(path(report, "format"), null)}`);
    return check();
  }
  let bytes: Uint8Array;
  try {
    bytes = reportSignedBytes(report);
  } catch (e) {
    problems.push(`the report cannot be read: ${(e as Error).message}`);
    return check();
  }
  const keyList = options.keys ?? [];
  const trusted = await packageTrustedKeys(isObject(pkg) ? pkg : {}, keyList, options.publicKey ?? null, problems);
  const key = keyId === null ? undefined : trusted.get(keyId);
  if (!key) {
    problems.push(`the report is signed with key ${keyId}, which the package does not trust`);
  } else if (revoked(pkg, keyList, keyId!)) {
    problems.push(`the report is signed with revoked key ${keyId}`);
  } else if (!(await key.verifyBase64(bytes, text(path(report, "signature"), "")!))) {
    problems.push("the signature of the report is not valid");
  }
  const checkpoint = path(pkg, "checkpoint");
  if (
    sequence === null ||
    sequence !== asLong(path(checkpoint, "sequence"), -1) ||
    text(path(evidence, "headHash"), null) !== text(path(checkpoint, "headHash"), null)
  ) {
    problems.push("the report is for another checkpoint than the package");
  }
  const scope = path(report, "scope");
  const packageScope = path(pkg, "scope");
  for (const field of ["agentId", "principalId", "fromSequence", "toSequence"]) {
    if (value(path(scope, field)) !== value(path(packageScope, field))) {
      problems.push(`the report has another ${field} than the package`);
    }
  }
  const hashes: string[] = [];
  for (const link of elements(path(pkg, "links"))) {
    if (isObject(path(link, "entry"))) {
      hashes.push(text(path(link, "hash"), "")!);
    }
  }
  if (
    entries === null ||
    entries !== hashes.length ||
    (await entriesDigest(hashes)) !== text(path(evidence, "entriesDigest"), "")
  ) {
    problems.push("the report covers other entries than the package discloses");
  }
  return check();
}

function revoked(pkg: unknown, keyList: Json[], keyId: string): boolean {
  const nodes = [...elements(path(pkg, "keys")), ...elements(keyList)];
  return nodes.some((node) => keyId === text(path(node, "keyId"), null) && isObject(path(node, "revocation")));
}

function value(node: unknown): string | number | null {
  if (node === undefined || node === null) {
    return null;
  }
  return integral(node) ?? text(node, "");
}

function object(node: unknown): Json {
  if (!isObject(node)) {
    throw new Error("a report must be a JSON object");
  }
  const map: Json = {};
  for (const [name, item] of Object.entries(node)) {
    if (item !== undefined) {
      map[name] = canonical(item);
    }
  }
  return map;
}

function canonical(node: unknown): unknown {
  if (node === null) {
    return null;
  }
  if (isObject(node)) {
    return object(node);
  }
  if (Array.isArray(node)) {
    return node.map(canonical);
  }
  if (typeof node === "string" || typeof node === "boolean") {
    return node;
  }
  if (integral(node) !== null) {
    return node;
  }
  throw new Error("reports hold only strings, integers, booleans, arrays and objects");
}

function integral(node: unknown): number | null {
  return typeof node === "number" && Number.isSafeInteger(node) ? node : null;
}

function asLong(node: unknown, fallback: number): number {
  if (typeof node === "number" && Number.isFinite(node)) {
    return Math.trunc(node);
  }
  if (typeof node === "string" && /^[+-]?\d+$/.test(node.trim())) {
    return Number(node.trim());
  }
  return fallback;
}

function text(node: unknown, fallback: string | null): string | null {
  if (typeof node === "string") {
    return node;
  }
  if (typeof node === "number" || typeof node === "boolean") {
    return String(node);
  }
  return node === undefined || node === null ? fallback : "";
}

function elements(node: unknown): unknown[] {
  return Array.isArray(node) ? node : isObject(node) ? Object.values(node) : [];
}

function path(node: unknown, field: string): unknown {
  return isObject(node) ? node[field] : undefined;
}

function isObject(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
