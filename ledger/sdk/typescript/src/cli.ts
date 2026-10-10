#!/usr/bin/env node
import { existsSync, readFileSync, statSync } from "node:fs";
import { inflateRawSync } from "node:zlib";
import { fromUtf8, toBase64 } from "./bytes.js";
import { EvidenceStatement, LogReceipt } from "./cose.js";
import { Json } from "./evidence.js";
import { PublicKey } from "./keys.js";
import { MandateCheck, MandateVerifier, StaticKeys, jwkPublicKey, mandatePayload } from "./mandate.js";
import { NoteKey } from "./note.js";
import { PackageReport, verifyPackage } from "./package.js";
import { ReportCheck, verifyReport } from "./report.js";
import { X509Certificate, readCertificates } from "./timestamp.js";
import { epochText } from "./timestamps.js";

export const VALID = 0;
export const INVALID = 1;
export const USAGE = 2;

export const USAGE_TEXT = `Usage: nexusphere-ledger-verify [--public-key <base64 X.509 Ed25519 key> | --public-key-file <file>] [--keys <keys.json>] [--witness <verifier key>]... [--witnesses-required <n>] [--timestamp-certificates <chain.pem>] [--require-timestamp] [--rekor-key <verifier key>]... [--require-rekor] [--json] <package.json>
       nexusphere-ledger-verify mandate --issuer <url> [--issuer <url>] [--audience <aud>] [--action <action> --target <target>] [--public-key <key> | --public-key-file <file>] [--skip-status] [--nonce <nonce>] [--require-key-binding] [--json] <token | token file | ->
       nexusphere-ledger-verify statement [--public-key <key> | --public-key-file <file>] [--receipt <receipt.cose>] <statement.cose>
       nexusphere-ledger-verify report [package options] --package <package.json> <report.json>
       nexusphere-ledger-verify dispute [package options] <dispute.zip>`;

export interface Io {
  out: (text: string) => void;
  err: (text: string) => void;
  stdin?: () => string;
}

class UsageError extends Error {}

const FLAGS = new Set(["--json", "--skip-status", "--require-key-binding", "--require-timestamp", "--require-rekor"]);
const PACKAGE_OPTIONS = [
  "--public-key", "--public-key-file", "--keys", "--witness", "--witnesses-required",
  "--timestamp-certificates", "--require-timestamp", "--rekor-key", "--require-rekor", "--json",
];
const MAX_FILE = 256 * 1024 * 1024;

function parse(args: string[], allowed: string[]): { options: Record<string, string[]>; positional: string | null } {
  const options: Record<string, string[]> = {};
  let positional: string | null = null;
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg.startsWith("--")) {
      if (!allowed.includes(arg)) {
        throw new UsageError();
      }
      if (FLAGS.has(arg)) {
        options[arg] = ["true"];
        continue;
      }
      if (i + 1 >= args.length) {
        throw new UsageError();
      }
      (options[arg] ??= []).push(args[++i]);
    } else if (positional === null) {
      positional = arg;
    } else {
      throw new UsageError();
    }
  }
  return { options, positional };
}

function publicKey(options: Record<string, string[]>): string | null {
  if (options["--public-key-file"]) {
    return readFileSync(options["--public-key-file"][0], "utf8").trim();
  }
  return options["--public-key"] ? options["--public-key"][0].trim() : null;
}

export async function run(args: string[], io: Io): Promise<number> {
  try {
    if (args[0] === "mandate") {
      return await mandate(args.slice(1), io);
    }
    if (args[0] === "statement") {
      return await statement(args.slice(1), io);
    }
    if (args[0] === "report") {
      return await report(args.slice(1), io);
    }
    if (args[0] === "dispute") {
      return await dispute(args.slice(1), io);
    }
    return await pkg(args[0] === "package" ? args.slice(1) : args, io);
  } catch (e) {
    if (e instanceof UsageError) {
      io.err(USAGE_TEXT + "\n");
      return USAGE;
    }
    throw e;
  }
}

async function pkg(args: string[], io: Io): Promise<number> {
  const { options, positional } = parse(args, PACKAGE_OPTIONS);
  if (positional === null) {
    throw new UsageError();
  }
  let data: Record<string, unknown>;
  let key: string | null;
  let keys: Record<string, unknown>[] | undefined;
  let certificates: X509Certificate[] = [];
  let reading = positional;
  try {
    key = publicKey(options);
    data = JSON.parse(readFileSync(positional, "utf8"));
    if (options["--keys"]) {
      reading = options["--keys"][0];
      keys = JSON.parse(readFileSync(reading, "utf8"));
    }
    if (options["--timestamp-certificates"]) {
      reading = options["--timestamp-certificates"][0];
      certificates = readCertificates(new Uint8Array(readFileSync(reading)));
    }
  } catch (e) {
    io.err(`Cannot read ${reading}: ${(e as Error).message}\n`);
    return USAGE;
  }
  const required = options["--witnesses-required"];
  if (required && !/^\d+$/.test(required[0])) {
    throw new UsageError();
  }
  let report: PackageReport;
  try {
    report = await verifyPackage(data, key, {
      witnesses: options["--witness"] ?? [],
      requiredWitnesses: required ? Number(required[0]) : undefined,
      keys,
      timestampCertificates: certificates,
      requireTimestamp: !!options["--require-timestamp"],
      rekorKeys: options["--rekor-key"] ?? [],
      requireRekor: !!options["--require-rekor"],
    });
  } catch {
    throw new UsageError();
  }
  if (options["--json"]) {
    io.out(JSON.stringify(report, null, 2) + "\n");
  } else {
    printPackage(report, certificates.length > 0, (options["--rekor-key"] ?? []).length > 0, io);
  }
  return report.valid ? VALID : INVALID;
}

interface Trust {
  key: string | null;
  keys: Json[] | undefined;
  certificates: X509Certificate[];
  witnesses: (string | NoteKey)[];
  required: number | undefined;
  rekorKeys: (string | NoteKey)[];
}

function witnessesRequired(options: Record<string, string[]>): number | undefined {
  const required = options["--witnesses-required"];
  if (required && !/^\d+$/.test(required[0])) {
    throw new UsageError();
  }
  return required ? Number(required[0]) : undefined;
}

async function report(args: string[], io: Io): Promise<number> {
  const { options, positional } = parse(args, [...PACKAGE_OPTIONS, "--package"]);
  if (positional === null || !options["--package"]) {
    throw new UsageError();
  }
  const required = witnessesRequired(options);
  let document: unknown;
  let data: Json;
  let key: string | null;
  let keys: Json[] | undefined;
  let certificates: X509Certificate[] = [];
  let reading = positional;
  try {
    key = publicKey(options);
    document = JSON.parse(readFileSync(positional, "utf8"));
    reading = options["--package"][0];
    data = JSON.parse(readFileSync(reading, "utf8"));
    if (options["--keys"]) {
      reading = options["--keys"][0];
      keys = JSON.parse(readFileSync(reading, "utf8"));
    }
    if (options["--timestamp-certificates"]) {
      reading = options["--timestamp-certificates"][0];
      certificates = readCertificates(new Uint8Array(readFileSync(reading)));
    }
  } catch (e) {
    io.err(`Cannot read ${reading}: ${(e as Error).message}\n`);
    return USAGE;
  }
  const trust = {
    key,
    keys,
    certificates,
    witnesses: options["--witness"] ?? [],
    required,
    rekorKeys: options["--rekor-key"] ?? [],
  };
  return finish(data, document, trust, options, [], io);
}

async function dispute(args: string[], io: Io): Promise<number> {
  const { options, positional } = parse(args, PACKAGE_OPTIONS);
  if (positional === null) {
    throw new UsageError();
  }
  const required = witnessesRequired(options);
  const rekorKeys: (string | NoteKey)[] = [...(options["--rekor-key"] ?? [])];
  const witnesses: (string | NoteKey)[] = [...(options["--witness"] ?? [])];
  const notes: string[] = [];
  let document: unknown;
  let data: Json;
  let key: string | null;
  let keys: Json[] | undefined;
  let certificates: X509Certificate[] = [];
  try {
    key = publicKey(options);
    const files = readZip(new Uint8Array(readFileSync(positional)));
    const json = (name: string) => JSON.parse(fromUtf8(files.get(name)!));
    if (!files.has("package.json") || !files.has("report.json")) {
      throw new Error("it has no package.json and report.json");
    }
    data = json("package.json");
    document = json("report.json");
    if (options["--keys"]) {
      keys = JSON.parse(readFileSync(options["--keys"][0], "utf8"));
    } else if (files.has("keys.json")) {
      keys = json("keys.json");
    }
    if (options["--timestamp-certificates"]) {
      certificates = readCertificates(new Uint8Array(readFileSync(options["--timestamp-certificates"][0])));
    } else if (files.has("timestamp-authority.pem")) {
      certificates = readCertificates(files.get("timestamp-authority.pem")!);
      notes.push("the timestamp authority chain");
    }
    if (rekorKeys.length === 0 && files.has("rekor.json")) {
      const rekor = json("rekor.json");
      if (rekor !== null && typeof rekor === "object" && typeof rekor.key === "string") {
        rekorKeys.push(await NoteKey.parse(rekor.key));
        notes.push("the Rekor log key");
      }
    }
    if (witnesses.length === 0 && files.has("witnesses.txt")) {
      for (const line of fromUtf8(files.get("witnesses.txt")!).split("\n")) {
        if (line.trim()) {
          witnesses.push(await NoteKey.parse(line.trim()));
        }
      }
      notes.push("the witness keys");
    }
  } catch (e) {
    io.err(`Cannot read ${positional}: ${(e as Error).message}\n`);
    return USAGE;
  }
  if (key === null) {
    notes.push("no ledger key; pass --public-key");
  }
  const trust = {
    key,
    keys,
    certificates,
    witnesses,
    required: required === undefined && !options["--witness"] ? 0 : required,
    rekorKeys,
  };
  return finish(data, document, trust, options, notes, io);
}

async function finish(
  data: Json,
  document: unknown,
  trust: Trust,
  options: Record<string, string[]>,
  notes: string[],
  io: Io,
): Promise<number> {
  let packageReport: PackageReport;
  try {
    packageReport = await verifyPackage(data, trust.key, {
      witnesses: trust.witnesses,
      requiredWitnesses: trust.required,
      keys: trust.keys,
      timestampCertificates: trust.certificates,
      requireTimestamp: !!options["--require-timestamp"],
      rekorKeys: trust.rekorKeys,
      requireRekor: !!options["--require-rekor"],
    });
  } catch {
    throw new UsageError();
  }
  const check = await verifyReport(document, data, { publicKey: trust.key, keys: trust.keys });
  const valid = packageReport.valid && check.valid;
  if (options["--json"]) {
    io.out(JSON.stringify({ valid, package: packageReport, report: check, takenFromArchive: notes }, null, 2) + "\n");
  } else {
    printPackage(packageReport, trust.certificates.length > 0, trust.rekorKeys.length > 0, io);
    printReport(check, notes, io);
  }
  return valid ? VALID : INVALID;
}

function printReport(check: ReportCheck, notes: string[], io: Io) {
  const lines = [
    "Nexusphere Ledger report",
    `  Report      : ${check.report} (${check.title})`,
    `  Signed by   : ${check.keyId}`,
    `  Evidence    : checkpoint ${check.checkpointSequence}, ${check.entries} entries`,
    `  Sections    : ${Object.entries(check.statuses)
      .map(([status, count]) => `${count} ${status.replace(/_/g, " ").toLowerCase()}`).join(", ")}`,
  ];
  if (notes.length) {
    lines.push(`  Archive     : taken from the archive itself: ${notes.join(", ")}`);
  }
  lines.push(check.valid ? "Result: VALID" : "Result: INVALID");
  if (!check.valid) {
    check.problems.forEach((problem) => lines.push("  - " + problem));
  }
  io.out(lines.join("\n") + "\n");
}

function readZip(data: Uint8Array): Map<string, Uint8Array> {
  const files = new Map<string, Uint8Array>();
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  let end = data.length - 22;
  while (end >= 0 && view.getUint32(end, true) !== 0x06054b50) {
    end--;
  }
  if (end < 0) {
    return files;
  }
  let offset = view.getUint32(end + 16, true);
  for (let count = view.getUint16(end + 10, true); count > 0; count--) {
    if (offset + 46 > data.length || view.getUint32(offset, true) !== 0x02014b50) {
      throw new Error("the central directory is damaged");
    }
    const method = view.getUint16(offset + 10, true);
    const crc = view.getUint32(offset + 16, true);
    const compressed = view.getUint32(offset + 20, true);
    const size = view.getUint32(offset + 24, true);
    const nameLength = view.getUint16(offset + 28, true);
    const local = view.getUint32(offset + 42, true);
    const name = fromUtf8(data.subarray(offset + 46, offset + 46 + nameLength));
    offset += 46 + nameLength + view.getUint16(offset + 30, true) + view.getUint16(offset + 32, true);
    if (local + 30 > data.length || view.getUint32(local, true) !== 0x04034b50) {
      throw new Error(`the local header of ${name} is damaged`);
    }
    if (size > MAX_FILE) {
      throw new Error(`${name} is larger than ${MAX_FILE} bytes`);
    }
    const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
    const raw = data.subarray(start, start + compressed);
    if (method !== 0 && method !== 8) {
      throw new Error("invalid compression method");
    }
    const content = method === 0 ? raw.slice() : new Uint8Array(inflateRawSync(raw, { maxOutputLength: size + 1 }));
    if (content.length !== size) {
      throw new Error(`invalid entry size (expected ${size} but got ${content.length} bytes)`);
    }
    if (crc32(content) !== crc) {
      throw new Error(`invalid entry CRC (expected 0x${crc.toString(16)} but got 0x${crc32(content).toString(16)})`);
    }
    files.set(name, content);
  }
  return files;
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) {
    c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  }
  return c >>> 0;
});

function crc32(data: Uint8Array): number {
  let c = 0xffffffff;
  for (const byte of data) {
    c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function printPackage(r: PackageReport, timestampPinned: boolean, rekorPinned: boolean, io: Io) {
  const note =
    r.pinnedKeyId === null
      ? " (taken from the package; pass --public-key to pin the ledger's key)"
      : r.pinnedKeyId === r.keyId
        ? " (pinned)"
        : ` (reached from pinned key ${r.pinnedKeyId} through signed key rotations)`;
  const lines = [
    "Nexusphere Ledger evidence package",
    `  Signing key : ${r.keyId}${note}`,
    `  Checkpoint  : sequence ${r.checkpointSequence}, signed at ${r.checkpointCreatedAt}`,
    ...(r.complianceProfiles.length ? [`  Compliance  : ${
      r.complianceProfiles.map((p) => `${p.id} (${p.digest.slice(0, 12)})`).join(", ")}, signed in the checkpoint`]
      : []),
    ...(r.timestampedAt !== null ? [`  Timestamp   : ${r.timestampedAt} by ${r.timestampAuthority}${
      timestampPinned ? " (pinned)" : " (pass --timestamp-certificates to pin the timestamp authority)"}`]
      : []),
    ...(r.rekorLogIndex !== null ? [`  Rekor       : entry ${r.rekorLogIndex} in ${r.rekorOrigin}${
      rekorPinned ? " (pinned)" : " (pass --rekor-key to pin the Rekor log)"}`]
      : []),
    `  Anchor      : ${r.anchorSequence === null ? "genesis" : "checkpoint " + r.anchorSequence}`,
    `  Chain       : ${r.checkedLinks} links from sequence ${r.firstSequence}`,
    `  Disclosed   : ${r.disclosedEntries} entries${r.agentId === null ? "" : ", agent " + r.agentId}${
      r.principalId === null ? "" : ", principal " + r.principalId}`,
  ];
  if (r.logTreeSize !== null) {
    lines.push(`  Log         : ${r.logOrigin}, ${r.logTreeSize} entries, ${r.provenEntries} disclosed entries proven`);
    lines.push(`  Receipts    : ${r.receiptedEntries} SCITT statements with receipts`);
    lines.push(`  Witnesses   : ${r.witnesses.length ? r.witnesses.join(", ") : "none"}`);
  }
  if (r.revokedKeys.length) {
    lines.push(
      `  Revoked     : ${r.revokedKeys.join(", ")}${
        r.valid ? " (witnesses prove the log checkpoint predates the compromise)" : ""}`,
    );
  }
  lines.push(r.valid ? "Result: VALID" : "Result: INVALID");
  if (!r.valid) {
    r.problems.forEach((problem) => lines.push("  - " + problem));
  }
  io.out(lines.join("\n") + "\n");
}

async function statement(args: string[], io: Io): Promise<number> {
  const { options, positional } = parse(args, ["--public-key", "--public-key-file", "--receipt"]);
  const keyText = publicKey(options);
  if (positional === null || keyText === null) {
    throw new UsageError();
  }
  let key: PublicKey;
  let parsed: EvidenceStatement;
  let receipt: LogReceipt | null = null;
  try {
    key = await PublicKey.fromBase64(keyText);
    parsed = EvidenceStatement.parse(new Uint8Array(readFileSync(positional)));
    if (options["--receipt"]) {
      receipt = LogReceipt.parse(new Uint8Array(readFileSync(options["--receipt"][0])));
    }
  } catch (e) {
    io.err(`Cannot read the statement or receipt: ${(e as Error).message}\n`);
    return USAGE;
  }
  const signed = await parsed.verify(key);
  const lines = [
    "Nexusphere Ledger evidence statement",
    `  Issuer      : ${parsed.issuer}`,
    `  Subject     : ${parsed.subject}`,
    `  Sequence    : ${parsed.sequence}`,
    `  Content hash: ${parsed.contentHash}`,
    `  Entry hash  : ${await parsed.entryHash()}`,
    `  Signature   : ${signed ? "valid, key " + parsed.keyId : "INVALID"}`,
  ];
  let proven = true;
  if (receipt) {
    const leaf = await parsed.leafHash();
    proven = await receipt.verify(leaf, key);
    lines.push(`  Receipt     : ${proven
      ? `valid, log ${receipt.issuer} at ${receipt.treeSize} entries, root ${toBase64((await receipt.root(leaf))!)}`
      : "INVALID"}`);
  }
  const valid = signed && proven;
  lines.push(valid ? "Result: VALID" : "Result: INVALID");
  io.out(lines.join("\n") + "\n");
  return valid ? VALID : INVALID;
}

async function mandate(args: string[], io: Io): Promise<number> {
  const { options, positional } = parse(args, [
    "--issuer", "--audience", "--action", "--target", "--public-key", "--public-key-file", "--skip-status", "--json",
    "--nonce", "--require-key-binding",
  ]);
  const issuers = options["--issuer"] ?? [];
  const action = options["--action"]?.[0] ?? null;
  const target = options["--target"]?.[0] ?? null;
  if (positional === null || issuers.length === 0 || (action === null) !== (target === null)) {
    throw new UsageError();
  }
  let token: string;
  let keyText: string | null;
  try {
    token = readToken(positional, io);
    keyText = publicKey(options);
  } catch (e) {
    io.err(`Cannot read ${positional}: ${(e as Error).message}\n`);
    return USAGE;
  }
  let keys: StaticKeys | undefined;
  if (keyText !== null) {
    let key: PublicKey;
    try {
      key = await PublicKey.fromBase64(keyText);
    } catch {
      io.err("The public key is not a base64 X.509 Ed25519 key\n");
      return USAGE;
    }
    keys = new StaticKeys(Object.fromEntries(issuers.map((issuer) => [issuer.replace(/\/$/, ""), { [key.keyId]: key }])));
  }
  const skipStatus = !!options["--skip-status"];
  const verifier = new MandateVerifier(issuers, {
    keys,
    skipStatus,
    audience: options["--audience"]?.[0] ?? null,
    requireKeyBinding: !!options["--require-key-binding"],
  });
  const nonce = options["--nonce"]?.[0] ?? null;
  const check = action === null
    ? await verifier.verifyBound(token, nonce)
    : await verifier.verifyBound(token, nonce, action, target);
  if (options["--json"]) {
    io.out(JSON.stringify({
      valid: check.valid,
      problems: check.problems,
      claims: check.claims === null ? null : mandatePayload(check.claims),
    }, null, 2) + "\n");
  } else {
    await printMandate(check, action, target, skipStatus, io);
  }
  return check.valid ? VALID : INVALID;
}

function readToken(source: string, io: Io): string {
  if (source === "-") {
    return (io.stdin ? io.stdin() : readFileSync(0, "ascii")).trim();
  }
  return existsSync(source) && statSync(source).isFile() ? readFileSync(source, "ascii").trim() : source.trim();
}

async function keyIdOf(jwk: Record<string, unknown>): Promise<string> {
  return (await jwkPublicKey(jwk)).keyId;
}

async function printMandate(check: MandateCheck, action: string | null, target: string | null, skipStatus: boolean, io: Io) {
  const lines = ["Nexusphere Ledger mandate"];
  const c = check.claims;
  if (c) {
    lines.push(`  Mandate     : ${c.mandateId} from grant ${c.grantId}`);
    lines.push(`  Issuer      : ${c.issuer}`);
    lines.push(`  Agent       : ${c.agentId} for principal ${c.principalId}`);
    lines.push(`  Audience    : ${c.audience ?? "any"}`);
    lines.push(`  Allows      : ${c.actions.join(", ")} on ${c.targets.join(", ")}${
      c.maxUses === null ? "" : `, at most ${c.maxUses} uses`}`);
    lines.push(`  Valid       : ${epochText(c.notBefore)} to ${epochText(c.expiresAt)}`);
    lines.push(`  Agent key   : ${c.holderKey ? await keyIdOf(c.holderKey) : "none"}`);
    lines.push(`  Status      : ${skipStatus ? "not checked" : `index ${c.statusIndex} in ${c.statusListUrl}`}`);
  }
  if (action !== null) {
    lines.push(`  Checked     : ${action} on ${target}`);
  }
  lines.push(check.valid ? "Result: VALID" : "Result: INVALID");
  if (!check.valid) {
    check.problems.forEach((p) => lines.push(`  - ${p.code}: ${p.message}`));
  }
  io.out(lines.join("\n") + "\n");
}

const entry = process.argv[1] ?? "";
if (entry.endsWith("cli.js") || entry.endsWith("nexusphere-ledger-verify")) {
  run(process.argv.slice(2), {
    out: (text) => process.stdout.write(text),
    err: (text) => process.stderr.write(text),
  }).then((code) => process.exit(code));
}
