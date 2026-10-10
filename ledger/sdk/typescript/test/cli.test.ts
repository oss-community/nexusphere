import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { INVALID, USAGE, VALID, run } from "../src/cli.js";
import { PrivateKey, fromHex, presentBound } from "../src/index.js";
import { ISSUER, LEDGER_KEY, buildPackage, entries, vector, witnessNoteKey } from "./support.js";

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
