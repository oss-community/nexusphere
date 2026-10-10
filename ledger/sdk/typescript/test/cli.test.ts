import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { INVALID, USAGE, VALID, run } from "../src/cli.js";
import { PrivateKey, fromHex, presentBound } from "../src/index.js";
import {
  ISSUER,
  LEDGER_KEY,
  buildPackage,
  entries,
  rekorPackage,
  timestampedPackage,
  vector,
  witnessNoteKey,
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
});
