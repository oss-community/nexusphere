import { describe, expect, it } from "vitest";
import {
  InvalidHttpSignature,
  PrivateKey,
  authority,
  contentDigest,
  signRequest,
  signatureBase,
  utf8,
  verifyRequest,
} from "../src/index.js";
import { LEDGER_KEY, vector } from "./support.js";

const URL_ = "https://ledger.supplier.test/a2a/in/sales";
const BODY = utf8('{"jsonrpc":"2.0"}');
const NOW = 1790841600;

describe("HTTP message signatures", async () => {
  const key = await PrivateKey.generate();
  const keys = (keyId: string) => (keyId === key.keyId ? key.publicKey : null);

  it("verifies a signed request", async () => {
    const headers = await signRequest("POST", URL_, BODY, key.keyId, key, NOW, "n-1");
    const verified = await verifyRequest("POST", URL_, headers, BODY, keys, NOW + 30);
    expect(verified).toEqual({ keyId: key.keyId, created: NOW, nonce: "n-1" });
    expect(headers["Content-Digest"]).toBe(await contentDigest(BODY));
    expect((await verifyRequest("POST", URL_, new Headers(headers), BODY, keys, NOW)).keyId).toBe(key.keyId);
  });

  it("signs a request without body", async () => {
    const headers = await signRequest("DELETE", URL_, null, key.keyId, key, NOW);
    expect(headers["Content-Digest"]).toBeUndefined();
    expect((await verifyRequest("DELETE", URL_, headers, null, keys, NOW)).keyId).toBe(key.keyId);
  });

  it("rejects changes, stale, unknown and missing signatures", async () => {
    const headers = await signRequest("POST", URL_, BODY, key.keyId, key, NOW);
    const cases: [string, string, Uint8Array, Record<string, string>, number][] = [
      ["POST", URL_, utf8("{}"), headers, NOW],
      ["PUT", URL_, BODY, headers, NOW],
      ["POST", "https://evil.test/a2a/in/sales", BODY, headers, NOW],
      ["POST", URL_, BODY, headers, NOW + 600],
      ["POST", URL_, BODY, {}, NOW],
      ["POST", URL_, BODY, { ...headers, "Signature-Input": headers["Signature-Input"].replace(' "content-digest"', "") }, NOW],
    ];
    for (const [method, url, body, h, at] of cases) {
      await expect(verifyRequest(method, url, h, body, keys, at)).rejects.toBeInstanceOf(InvalidHttpSignature);
    }
    await expect(verifyRequest("POST", URL_, headers, BODY, () => null, NOW)).rejects.toThrow("unknown");
  });

  it("leaves the default port out of the authority", () => {
    expect(authority("https://Ledger.Test:443/x")).toBe("ledger.test");
    expect(authority("http://localhost:8090/x")).toBe("localhost:8090");
  });
});

describe("http-signature.json", () => {
  it("rebuilds and verifies the signature", async () => {
    const v = vector("http-signature.json");
    const body = utf8(v.body);
    const headers = await signRequest(v.method, v.url, body, v.keyId, LEDGER_KEY, v.created, v.nonce);
    expect(headers).toEqual(v.headers);
    const lower = Object.fromEntries(Object.entries(headers).map(([k, value]) => [k.toLowerCase(), value]));
    const params = headers["Signature-Input"].substring(headers["Signature-Input"].indexOf("=") + 1);
    expect(signatureBase(v.method, v.url, ["@method", "@authority", "@path", "content-digest"], (n) => lower[n], params))
      .toBe(v.signatureBase);
    const verified = await verifyRequest(v.method, v.url, v.headers, body,
      (keyId) => (keyId === v.keyId ? LEDGER_KEY.publicKey : null), v.created + 10);
    expect(verified.keyId).toBe(v.keyId);
  });
});
