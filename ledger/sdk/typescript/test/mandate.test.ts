import { describe, expect, it } from "vitest";
import {
  Disclosure,
  JwksKeys,
  MandateVerifier,
  MandateVerifierOptions,
  PrivateKey,
  SdJwt,
  StaticKeys,
  StatusList,
  TYPE,
  VCT,
  jwkOf,
} from "../src/index.js";
import { ISSUER, LEDGER_KEY, vector } from "./support.js";

const NOW = 1790841600 + 3600;
const STATUS = ISSUER + "/public/v1/mandates/status";

async function issue(options: { key?: PrivateKey; issuer?: string; index?: number; exp?: number; nbf?: number; status?: string } = {}) {
  const key = options.key ?? LEDGER_KEY;
  const nbf = options.nbf ?? 1790841600;
  const disclosures = [Disclosure.of("principal", "globex"), Disclosure.of("grant", "00000000-0000-4000-8000-0000000000bb")];
  const digests = (await Promise.all(disclosures.map((d) => d.digest()))).sort();
  const payload = {
    iss: options.issuer ?? ISSUER,
    vct: VCT,
    sub: "sales-agent",
    aud: "https://supplier.example",
    jti: "00000000-0000-4000-8000-0000000000aa",
    iat: nbf,
    nbf,
    exp: options.exp ?? 1822377600,
    mandate: { actions: ["a2a/send"], targets: ["supplier/*"], maxUses: 5, _sd: digests },
    status: { status_list: { idx: options.index ?? 3, uri: options.status ?? STATUS } },
    _sd_alg: "sha-256",
  };
  return SdJwt.issue({ alg: "EdDSA", typ: TYPE, kid: key.keyId }, payload, disclosures, key);
}

function fakeIssuer(revoked: number[] = [], exp = NOW + 600) {
  const calls: string[] = [];
  const fetcher = async (url: string) => {
    calls.push(url);
    if (url === ISSUER + "/public/v1/keys") {
      return JSON.stringify({ keys: [jwkOf(LEDGER_KEY.publicKey)] });
    }
    if (url === STATUS) {
      return StatusList.of(ISSUER, STATUS, NOW - 60, exp, 16, revoked).sign(LEDGER_KEY.keyId, LEDGER_KEY);
    }
    throw new Error("404 " + url);
  };
  return { fetcher, calls };
}

function verifier(options: MandateVerifierOptions = {}) {
  return new MandateVerifier(ISSUER, { fetcher: fakeIssuer().fetcher, clock: () => NOW, ...options });
}

describe("MandateVerifier", () => {
  it("accepts a mandate with keys and status from the issuer", async () => {
    const issuer = fakeIssuer([2, 4]);
    const v = verifier({ fetcher: issuer.fetcher, audience: "https://supplier.example" });
    const check = await v.verifyAction(await issue(), "a2a/send", "supplier/sales");
    expect(check.problems).toEqual([]);
    expect(check.claims!.principalId).toBe("globex");
    await v.verify(await issue());
    expect(issuer.calls.length).toBe(2);
  });

  it("finds revoked mandates and unavailable status", async () => {
    expect((await verifier({ fetcher: fakeIssuer([3]).fetcher }).verify(await issue())).has("REVOKED")).toBe(true);
    expect((await verifier().verify(await issue({ index: 99 }))).has("REVOKED")).toBe(true);
    expect((await verifier({ fetcher: fakeIssuer([], NOW - 1).fetcher }).verify(await issue())).has("STATUS_UNAVAILABLE")).toBe(true);
    expect((await verifier().verify(await issue({ status: "https://elsewhere.example/status" }))).has("STATUS_UNAVAILABLE")).toBe(true);
  });

  it("checks time, audience and coverage", async () => {
    const check = await verifier({ skipStatus: true, audience: "https://other.example" }).verifyAction(
      await issue({ exp: NOW - 120, nbf: NOW - 7200 }),
      "a2a/send",
      "buyer/orders",
    );
    expect(new Set(check.problems.map((p) => p.code))).toEqual(new Set(["EXPIRED", "WRONG_AUDIENCE", "NOT_COVERED"]));
    expect((await verifier({ skipStatus: true }).verify(await issue({ nbf: NOW + 120 }))).has("NOT_YET_VALID")).toBe(true);
    expect((await verifier({ skipStatus: true }).verify(await issue({ exp: NOW - 30, nbf: NOW - 7200 }))).valid).toBe(true);
  });

  it("checks issuer, key and signature", async () => {
    const other = await PrivateKey.generate();
    expect((await verifier().verify(await issue({ issuer: "https://evil.example" }))).has("UNTRUSTED_ISSUER")).toBe(true);
    expect((await verifier().verify(await issue({ key: other }))).has("UNKNOWN_KEY")).toBe(true);
    const token = await issue();
    const [jwt, ...rest] = token.split("~");
    const [head, payload, signature] = jwt.split(".");
    const forged = [head, payload, signature.slice(0, -4) + (signature.endsWith("AAAA") ? "BBBB" : "AAAA")].join(".");
    expect((await verifier().verify([forged, ...rest].join("~"))).has("BAD_SIGNATURE")).toBe(true);
    const fixed = new MandateVerifier(ISSUER, {
      keys: StaticKeys.fixed(ISSUER, other.publicKey),
      skipStatus: true,
      clock: () => NOW,
    });
    expect((await fixed.verify(await issue({ key: other }))).valid).toBe(true);
  });

  it("rejects malformed tokens", async () => {
    expect((await verifier().verify("not-a-token")).has("MALFORMED")).toBe(true);
    const token = await issue();
    expect((await verifier().verify(token + token.split("~")[1] + "~")).has("MALFORMED")).toBe(true);
    expect((await verifier().verify(token + Disclosure.of("principal", "initech").encoded + "~")).has("MALFORMED")).toBe(true);
  });

  it("verifies a selective presentation", async () => {
    const presented = SdJwt.parse(await issue()).present(["grant"]);
    const check = await verifier({ skipStatus: true }).verify(presented);
    expect(check.valid).toBe(true);
    expect(check.claims!.principalId).toBeNull();
    expect(check.claims!.grantId).toBe("00000000-0000-4000-8000-0000000000bb");
  });

  it("verifies the mandate vector with JWKS", async () => {
    const keys = new JwksKeys(fakeIssuer().fetcher, 300, () => NOW);
    const check = await new MandateVerifier(ISSUER, { keys, skipStatus: true, clock: () => NOW }).verify(
      vector("mandate.json").token,
    );
    expect(check.valid).toBe(true);
  });
});
