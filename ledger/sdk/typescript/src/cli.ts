#!/usr/bin/env node
import { existsSync, readFileSync, statSync } from "node:fs";
import { toBase64 } from "./bytes.js";
import { EvidenceStatement, LogReceipt } from "./cose.js";
import { PublicKey } from "./keys.js";
import { MandateCheck, MandateVerifier, StaticKeys, mandatePayload } from "./mandate.js";
import { PackageReport, verifyPackage } from "./package.js";
import { epochText } from "./timestamps.js";

export const VALID = 0;
export const INVALID = 1;
export const USAGE = 2;

export const USAGE_TEXT = `Usage: nexusphere-ledger-verify [--public-key <base64 X.509 Ed25519 key> | --public-key-file <file>] [--witness <verifier key>]... [--witnesses-required <n>] [--json] <package.json>
       nexusphere-ledger-verify mandate --issuer <url> [--issuer <url>] [--audience <aud>] [--action <action> --target <target>] [--public-key <key> | --public-key-file <file>] [--skip-status] [--json] <token | token file | ->
       nexusphere-ledger-verify statement [--public-key <key> | --public-key-file <file>] [--receipt <receipt.cose>] <statement.cose>`;

export interface Io {
  out: (text: string) => void;
  err: (text: string) => void;
  stdin?: () => string;
}

class UsageError extends Error {}

const FLAGS = new Set(["--json", "--skip-status"]);

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
  const { options, positional } = parse(args, [
    "--public-key", "--public-key-file", "--witness", "--witnesses-required", "--json",
  ]);
  if (positional === null) {
    throw new UsageError();
  }
  let data: Record<string, unknown>;
  let key: string | null;
  try {
    key = publicKey(options);
    data = JSON.parse(readFileSync(positional, "utf8"));
  } catch (e) {
    io.err(`Cannot read ${positional}: ${(e as Error).message}\n`);
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
    });
  } catch {
    throw new UsageError();
  }
  if (options["--json"]) {
    io.out(JSON.stringify(report, null, 2) + "\n");
  } else {
    printPackage(report, io);
  }
  return report.valid ? VALID : INVALID;
}

function printPackage(r: PackageReport, io: Io) {
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
  const verifier = new MandateVerifier(issuers, { keys, skipStatus, audience: options["--audience"]?.[0] ?? null });
  const check = action === null ? await verifier.verify(token) : await verifier.verifyAction(token, action, target);
  if (options["--json"]) {
    io.out(JSON.stringify({
      valid: check.valid,
      problems: check.problems,
      claims: check.claims === null ? null : mandatePayload(check.claims),
    }, null, 2) + "\n");
  } else {
    printMandate(check, action, target, skipStatus, io);
  }
  return check.valid ? VALID : INVALID;
}

function readToken(source: string, io: Io): string {
  if (source === "-") {
    return (io.stdin ? io.stdin() : readFileSync(0, "ascii")).trim();
  }
  return existsSync(source) && statSync(source).isFile() ? readFileSync(source, "ascii").trim() : source.trim();
}

function printMandate(check: MandateCheck, action: string | null, target: string | null, skipStatus: boolean, io: Io) {
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
