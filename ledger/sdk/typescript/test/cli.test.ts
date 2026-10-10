import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { INVALID, USAGE, VALID, run } from "../src/cli.js";
import { PrivateKey, fromHex, presentBound, sha256Hex } from "../src/index.js";
import {
  ISSUER,
  LEDGER_KEY,
  buildPackage,
  buildReport,
  entries,
  keyRecord,
  rekorPackage,
  timestampedPackage,
  vector,
  witnessNoteKey,
  zip,
} from "./support.js";

async function cli(...args: string[]) {
  let out = "";
  let err = "";
  const code = await run(args, { out: (text) => (out += text), err: (text) => (err += text) });
  return { code, out, err };
}

describe("nexusphere-ledger-verify", () => {
  const directory = mkdtempSync(join(tmpdir(), "nexusphere-cli-"));

  it("verifies a package", async () => {
    const path = join(directory, "package.json");
    writeFileSync(path, JSON.stringify(await buildPackage(await entries(4), [2], { witness: true })));
    const witness = (await witnessNoteKey()).vkey;
    const valid = await cli("--public-key", LEDGER_KEY.publicKey.encoded, "--witness", witness, path);
    expect(valid.code).toBe(VALID);
    expect(valid.out).toContain("(pinned)");
    expect(valid.out).toContain("Witnesses   : witness.example");
    const json = await cli("--json", "--witnesses-required", "2", "--witness", witness, path);
    expect(json.code).toBe(INVALID);
    expect(JSON.parse(json.out).valid).toBe(false);
    expect((await cli()).code).toBe(USAGE);
    expect((await cli(join(directory, "missing.json"))).code).toBe(USAGE);
  });

  it("prints the prompt texts a package discloses", async () => {
    const prompt = "Pay approved invoices only.";
    const promptHash = await sha256Hex(prompt);
    const pkg = await buildPackage(await entries(3, undefined, undefined, { 2: { name: "model-a", promptHash } }), [2]);
    const path = join(directory, "prompts.json");
    writeFileSync(path, JSON.stringify(pkg));
    expect((await cli(path)).out).not.toContain("Prompts");
    writeFileSync(path, JSON.stringify({ ...pkg, prompts: [{ hash: promptHash, text: prompt }] }));
    const result = await cli(path);
    expect(result.code).toBe(VALID);
    expect(result.out).toMatch(/ {2}Disclosed {3}: 1 entries\n {2}Prompts {5}: 1 prompt texts match the hashes their entries name\n/);
  });

  it("verifies the timestamp of a package", async () => {
    const v = vector("timestamp.json");
    const path = join(directory, "timestamped.json");
    const chain = join(directory, "chain.pem");
    const root = join(directory, "root.pem");
    writeFileSync(path, JSON.stringify(await timestampedPackage()));
    writeFileSync(chain, v.certificates.slice(1).join(""));
    writeFileSync(root, v.certificates[2]);
    const key = LEDGER_KEY.publicKey.encoded;
    const pinned = await cli("--public-key", key, "--timestamp-certificates", chain, "--require-timestamp", path);
    expect(pinned.code).toBe(VALID);
    expect(pinned.out).toContain(`  Timestamp   : ${v.genTime} by ${v.authority} (pinned)\n`);
    const unpinned = await cli("--public-key", key, path);
    expect(unpinned.out).toContain("(pass --timestamp-certificates to pin the timestamp authority)");
    const untrusted = await cli("--json", "--public-key", key, "--timestamp-certificates", root, path);
    expect(untrusted.code).toBe(INVALID);
    expect(JSON.parse(untrusted.out).problems).toEqual([
      "the timestamp of checkpoint 3: the timestamp authority is not trusted",
    ]);
    const missing = join(directory, "untimed.json");
    writeFileSync(missing, JSON.stringify(await buildPackage(await entries(2), [1])));
    const required = await cli("--require-timestamp", missing);
    expect(required.code).toBe(INVALID);
    expect(required.out).toContain("checkpoint 2 has no timestamp");
    const garbage = join(directory, "garbage.pem");
    writeFileSync(garbage, "not a certificate");
    expect((await cli("--timestamp-certificates", garbage, path)).code).toBe(USAGE);
  });

  it("verifies the Rekor entry of a package", async () => {
    const v = vector("rekor.json");
    const path = join(directory, "rekor.json");
    writeFileSync(path, JSON.stringify(await rekorPackage()));
    const key = LEDGER_KEY.publicKey.encoded;
    const pinned = await cli("--public-key", key, "--rekor-key", v.rekorVerifierKey, "--require-rekor",
      "--witnesses-required", "0", path);
    expect(pinned.code).toBe(VALID);
    expect(pinned.out).toContain(`  Rekor       : entry 1 in rekor.local (pinned)\n  Anchor      :`);
    expect(pinned.out.indexOf("  Timestamp   :")).toBeLessThan(pinned.out.indexOf("  Rekor       :"));
    const unpinned = await cli("--public-key", key, "--witnesses-required", "0", path);
    expect(unpinned.out).toContain("  Rekor       : entry 1 in rekor.local (pass --rekor-key to pin the Rekor log)\n");
    const json = await cli("--json", "--public-key", key, "--rekor-key", (await witnessNoteKey()).vkey,
      "--witnesses-required", "0", path);
    expect(json.code).toBe(INVALID);
    expect(JSON.parse(json.out).problems).toEqual([
      "the Rekor entry of checkpoint 3: the log checkpoint is not signed by a pinned Rekor key",
    ]);
    const missing = join(directory, "unanchored.json");
    writeFileSync(missing, JSON.stringify(await buildPackage(await entries(2), [1])));
    const required = await cli("--require-rekor", missing);
    expect(required.code).toBe(INVALID);
    expect(required.out).toContain("checkpoint 2 is not anchored in Rekor");
    expect((await cli("--rekor-key", "not a key", path)).code).toBe(USAGE);
  });

  it("verifies a statement and its receipt", async () => {
    const item = vector("scitt.json").items[0];
    const statement = join(directory, "statement.cose");
    const receipt = join(directory, "receipt.cose");
    writeFileSync(statement, fromHex(item.statement));
    writeFileSync(receipt, fromHex(item.receipt));
    const result = await cli("statement", "--public-key", LEDGER_KEY.publicKey.encoded, "--receipt", receipt, statement);
    expect(result.code).toBe(VALID);
    expect(result.out).toContain("Sequence    : 1");
  });

  it("verifies a mandate", async () => {
    const token = vector("mandate.json").token;
    const key = LEDGER_KEY.publicKey.encoded;
    const valid = await cli("mandate", "--issuer", ISSUER, "--public-key", key, "--skip-status", "--action", "a2a/send",
      "--target", "supplier/sales", token);
    expect(valid.code).toBe(VALID);
    expect(valid.out).toContain("Agent       : sales-agent for principal globex");
    const other = await cli("mandate", "--issuer", "https://other.example", "--public-key", key, "--skip-status", "--json", token);
    expect(other.code).toBe(INVALID);
    expect(JSON.parse(other.out).problems[0].code).toBe("UNTRUSTED_ISSUER");
  });

  it("verifies a key-bound mandate", async () => {
    const v = vector("mandate-key-binding.json");
    const agent = await PrivateKey.fromSeed(fromHex(v.agentSeed));
    const presented = await presentBound(v.token, agent, v.audience, v.nonce);
    const common = ["mandate", "--issuer", ISSUER, "--public-key", LEDGER_KEY.publicKey.encoded, "--skip-status"];
    const valid = await cli(...common, "--nonce", v.nonce, presented);
    expect(valid.code).toBe(VALID);
    expect(valid.out).toContain("Agent key   : " + agent.keyId);
    const replayed = await cli(...common, "--nonce", "00".repeat(32), presented);
    expect(replayed.code).toBe(INVALID);
    expect(replayed.out).toContain("KEY_BINDING_INVALID");
    const unbound = await cli(...common, "--require-key-binding", vector("mandate.json").token);
    expect(unbound.code).toBe(INVALID);
    expect(unbound.out).toContain("KEY_NOT_BOUND");
  });

  it("verifies a report against its package", async () => {
    const pkg = await buildPackage(await entries(4), [2], { witness: true });
    const packagePath = join(directory, "report-package.json");
    const reportPath = join(directory, "report.json");
    writeFileSync(packagePath, JSON.stringify(pkg));
    writeFileSync(reportPath, JSON.stringify(await buildReport(pkg)));
    const witness = (await witnessNoteKey()).vkey;
    const key = LEDGER_KEY.publicKey.encoded;
    const valid = await cli("report", "--public-key", key, "--witness", witness, "--package", packagePath, reportPath);
    expect(valid.code).toBe(VALID);
    expect(valid.out).toContain(
      "Result: VALID\n" +
        "Nexusphere Ledger report\n" +
        "  Report      : dispute (Dispute package)\n" +
        `  Signed by   : ${LEDGER_KEY.keyId}\n` +
        "  Evidence    : checkpoint 4, 1 entries\n" +
        "  Sections    : 1 shown\n" +
        "Result: VALID\n",
    );
    expect(valid.out).not.toContain("Archive");
    const json = await cli("report", "--json", "--public-key", key, "--witnesses-required", "0", "--package",
      packagePath, reportPath);
    const result = JSON.parse(json.out);
    expect(json.code).toBe(VALID);
    expect(Object.keys(result)).toEqual(["valid", "package", "report", "takenFromArchive"]);
    expect(result).toMatchObject({ valid: true, package: { valid: true }, report: { valid: true, entries: 1 } });
    expect(result.takenFromArchive).toEqual([]);
    const other = join(directory, "other-package.json");
    writeFileSync(other, JSON.stringify(await buildPackage(await entries(4), [3], { witness: true })));
    const invalid = await cli("report", "--public-key", key, "--witness", witness, "--package", other, reportPath);
    expect(invalid.code).toBe(INVALID);
    expect(invalid.out).toContain("Result: INVALID\n  - the report covers other entries than the package discloses\n");
    expect((await cli("report", reportPath)).code).toBe(USAGE);
    expect((await cli("dispute", "--package", packagePath, reportPath)).code).toBe(USAGE);
    const missing = await cli("report", "--package", join(directory, "missing.json"), reportPath);
    expect(missing.code).toBe(USAGE);
    expect(missing.err).toContain("Cannot read " + join(directory, "missing.json"));
  });

  it("verifies an index proof", async () => {
    const v = vector("index-proof.json");
    const present = join(directory, "index-present.json");
    const absent = join(directory, "index-absent.json");
    const packagePath = join(directory, "index-package.json");
    writeFileSync(present, JSON.stringify(v.proofs[0]));
    writeFileSync(absent, JSON.stringify(v.proofs[2]));
    const index = v.proofs[0].index;
    writeFileSync(packagePath, JSON.stringify({ checkpoint: { sequence: 3, headHash: index.headHash } }));
    const key = LEDGER_KEY.publicKey.encoded;
    const valid = await cli("index", "--public-key", key, "--package", packagePath, present);
    expect(valid.code).toBe(VALID);
    expect(valid.out).toBe(
      "Nexusphere Ledger index proof\n" +
        "  Term        : agent invoice-agent\n" +
        `  Index       : checkpoint 3, head ${index.headHash}\n` +
        `  Signed by   : ${LEDGER_KEY.keyId}\n` +
        "  Finding     : 2 entries, sequences 1 to 2\n" +
        "  Package     : checkpoint 3\n" +
        "Result: VALID\n",
    );
    const unpinned = await cli("index", absent);
    expect(unpinned.code).toBe(VALID);
    expect(unpinned.out).toContain(
      `  Signed by   : ${LEDGER_KEY.keyId} (taken from the proof; pass --public-key to pin the ledger's key)\n` +
        "  Finding     : no entry up to sequence 3\n" +
        "Result: VALID\n",
    );
    const json = await cli("index", "--json", "--public-key", key, absent);
    expect(Object.keys(JSON.parse(json.out))).toEqual([
      "valid", "term", "present", "count", "firstSequence", "lastSequence", "sequence", "headHash", "keyId", "problems",
    ]);
    expect(JSON.parse(json.out)).toMatchObject({ valid: true, present: false, count: null, sequence: 3 });
    writeFileSync(present, JSON.stringify({ ...v.proofs[0], leaf: { ...v.proofs[0].leaf, count: 1 } }));
    const invalid = await cli("index", "--public-key", key, present);
    expect(invalid.code).toBe(INVALID);
    expect(invalid.out).toContain("Result: INVALID\n  - the proof does not lead to the signed root\n");
    expect((await cli("index")).code).toBe(USAGE);
    const missing = await cli("index", "--package", join(directory, "missing.json"), absent);
    expect(missing.code).toBe(USAGE);
    expect(missing.err).toContain("Cannot read " + join(directory, "missing.json"));
  });

  it("verifies a dispute package", async () => {
    const pkg = await buildPackage(await entries(4), [2], { witness: true });
    const files = {
      "README.txt": "Nexusphere Ledger dispute package\n",
      "package.json": JSON.stringify(pkg, null, 2),
      "report.json": JSON.stringify(await buildReport(pkg), null, 2),
      "keys.json": JSON.stringify([keyRecord(LEDGER_KEY)]),
      "witnesses.txt": (await witnessNoteKey()).vkey + "\n",
    };
    const key = LEDGER_KEY.publicKey.encoded;
    for (const [name, options] of [
      ["stored.zip", {}],
      ["deflated.zip", { deflate: true, descriptor: true }],
    ] as const) {
      const path = join(directory, name);
      writeFileSync(path, zip(files, options));
      const valid = await cli("dispute", "--public-key", key, path);
      expect(valid.code).toBe(VALID);
      expect(valid.out).toContain("  Witnesses   : witness.example\n");
      expect(valid.out).toContain(
        "  Sections    : 1 shown\n  Archive     : taken from the archive itself: the witness keys\nResult: VALID\n",
      );
    }
    const path = join(directory, "stored.zip");
    const unpinned = await cli("dispute", "--json", path);
    expect(unpinned.code).toBe(VALID);
    expect(JSON.parse(unpinned.out).takenFromArchive).toEqual(["the witness keys", "no ledger key; pass --public-key"]);
    const pinnedWitness = await cli("dispute", "--json", "--public-key", key, "--witness", (await witnessNoteKey()).vkey,
      path);
    expect(JSON.parse(pinnedWitness.out).takenFromArchive).toEqual([]);
    const changed = join(directory, "changed.zip");
    const report = await buildReport(pkg);
    writeFileSync(changed, zip({ ...files, "report.json": JSON.stringify({ ...report, title: "Another title" }) }));
    const invalid = await cli("dispute", "--public-key", key, changed);
    expect(invalid.code).toBe(INVALID);
    expect(invalid.out).toContain("  Report      : dispute (Another title)\n");
    expect(invalid.out).toContain("Result: INVALID\n  - the signature of the report is not valid\n");
    const empty = join(directory, "empty.zip");
    writeFileSync(empty, zip({ "package.json": "{}" }));
    const unreadable = await cli("dispute", empty);
    expect(unreadable.code).toBe(USAGE);
    expect(unreadable.err).toBe(`Cannot read ${empty}: it has no package.json and report.json\n`);
    const damaged = join(directory, "damaged.zip");
    const bytes = zip(files);
    bytes[40] ^= 1;
    writeFileSync(damaged, bytes);
    expect((await cli("dispute", damaged)).err).toContain("invalid entry CRC");
  });

  it("takes the timestamp authority and the Rekor key from a dispute package", async () => {
    const pkg = await rekorPackage();
    const path = join(directory, "anchored.zip");
    writeFileSync(path, zip({
      "package.json": JSON.stringify(pkg),
      "report.json": JSON.stringify(await buildReport(pkg)),
      "timestamp-authority.pem": vector("timestamp.json").certificates.slice(1).join(""),
      "rekor.json": JSON.stringify({ origin: "rekor.local", key: vector("rekor.json").rekorVerifierKey }),
    }, { deflate: true }));
    const result = await cli("dispute", "--public-key", LEDGER_KEY.publicKey.encoded, path);
    expect(result.code).toBe(VALID);
    expect(result.out).toContain("  Rekor       : entry 1 in rekor.local (pinned)\n");
    expect(result.out).toMatch(/  Timestamp   : .* \(pinned\)\n/);
    expect(result.out).toContain(
      "  Archive     : taken from the archive itself: the timestamp authority chain, the Rekor log key\n",
    );
  });
});
