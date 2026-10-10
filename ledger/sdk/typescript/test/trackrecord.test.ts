import { describe, expect, it } from "vitest";
import {
  Jws,
  PrivateKey,
  SdJwt,
  StaticKeys,
  TrackRecordClaims,
  TrackRecordVerifier,
  TrackRecordVerifierOptions,
  checkedTrackRecord,
  confirmationJwk,
  issueTrackRecord,
  matchTrackRecord,
  presentBound,
  trackRecordClaims,
  trackRecordOf,
  trackRecordPayload,
  verifyIndexProof,
} from "../src/index.js";
import { ISSUER, LEDGER_KEY, vector } from "./support.js";

const NOW = 1790841600 + 3600;
const AUDIENCE = "https://buyer.example";
const NONCE = "ab".repeat(32);

function claims(changes: Partial<TrackRecordClaims> = {}): TrackRecordClaims {
  return { ...trackRecordClaims(vector("agent-track-record.json").claims), ...changes };
}

function issue(changes: Partial<TrackRecordClaims> = {}, key: PrivateKey = LEDGER_KEY): Promise<string> {
  return issueTrackRecord(claims(changes), key.keyId, key);
}

function verifier(options: TrackRecordVerifierOptions = {}) {
  return new TrackRecordVerifier(ISSUER, { keys: StaticKeys.fixed(ISSUER, LEDGER_KEY.publicKey), clock: () => NOW,
    ...options });
}

describe("TrackRecordVerifier", () => {
  it("verifies a track record issued here and its selective presentations", async () => {
    const token = await issue({ audience: AUDIENCE });
    const check = await verifier({ audience: AUDIENCE }).verify(token);
    expect(check.problems).toEqual([]);
    expect(check.claims).toEqual(claims({ audience: AUDIENCE }));
    expect(await trackRecordOf(token)).toEqual(claims({ audience: AUDIENCE }));
    const presented = await verifier().verify(SdJwt.parse(token).present(["principals"]));
    expect(presented.valid).toBe(true);
    expect(presented.claims!.principals).toBe(1);
    expect(presented.claims!.outcomes).toBeNull();
    expect(presented.claims!.actions).toBeNull();
  });

  it("verifies a track record without entries", async () => {
    const empty = claims({ total: 0, firstSequence: null, lastSequence: null, firstAt: null, lastAt: null,
      outcomes: {}, principals: 0, actions: {} });
    const check = await verifier().verify(await issueTrackRecord(empty, LEDGER_KEY.keyId, LEDGER_KEY));
    expect(check.problems).toEqual([]);
    expect(check.claims).toEqual(empty);
    expect((trackRecordPayload(check.claims!).record as Record<string, unknown>).firstSequence).toBeUndefined();
  });

  it("finds time and audience problems", async () => {
    expect((await verifier().verify(await issue({ issuedAt: NOW + 120 }))).has("NOT_YET_VALID")).toBe(true);
    expect((await verifier().verify(await issue({ expiresAt: NOW - 120 }))).has("EXPIRED")).toBe(true);
    expect((await verifier({ audience: AUDIENCE }).verify(await issue({ audience: "https://other.example" })))
      .has("WRONG_AUDIENCE")).toBe(true);
    expect((await verifier({ audience: AUDIENCE }).verify(await issue())).valid).toBe(true);
  });

  it("finds issuer, key, signature and format problems", async () => {
    const other = await PrivateKey.generate();
    expect((await verifier().verify(await issue({ issuer: "https://evil.example" }))).has("UNTRUSTED_ISSUER")).toBe(true);
    expect((await verifier().verify(await issue({}, other))).has("UNKNOWN_KEY")).toBe(true);
    const token = await issue();
    const jwt = token.split("~")[0];
    const [head, payload, signature] = jwt.split(".");
    const forged = [head, payload, signature.slice(0, -4) + (signature.endsWith("AAAA") ? "BBBB" : "AAAA")].join(".");
    expect((await verifier().verify(forged + token.substring(jwt.length))).has("BAD_SIGNATURE")).toBe(true);
    expect((await verifier().verify(vector("mandate.json").token)).has("MALFORMED")).toBe(true);
    const typed = await Jws.sign({ alg: "EdDSA", typ: "JWT", kid: LEDGER_KEY.keyId }, trackRecordPayload(claims()),
      LEDGER_KEY);
    expect((await verifier().verify(typed + "~")).has("WRONG_TYPE")).toBe(true);
    expect((await verifier().verify("not-a-token")).has("MALFORMED")).toBe(true);
    expect(() => checkedTrackRecord(claims({ headHash: "00" }))).toThrow();
  });

  it("checks the key binding of a bound track record", async () => {
    const agent = await PrivateKey.generate();
    const bound = await issue({ holderKey: confirmationJwk(agent.publicKey), audience: AUDIENCE });
    const presented = await presentBound(bound, agent, AUDIENCE, NONCE, NOW - 5);
    const check = await verifier().verifyBound(presented, NONCE);
    expect(check.problems).toEqual([]);
    expect(check.claims!.holderKey).toEqual(confirmationJwk(agent.publicKey));
    expect((await verifier().verifyBound(bound, NONCE)).has("KEY_BINDING_MISSING")).toBe(true);
    expect((await verifier().verifyBound(presented, "cd".repeat(32))).has("KEY_BINDING_INVALID")).toBe(true);
    const stolen = await presentBound(bound, await PrivateKey.generate(), AUDIENCE, NONCE, NOW);
    expect((await verifier().verifyBound(stolen, NONCE)).has("KEY_BINDING_INVALID")).toBe(true);
    const unbound = await presentBound(await issue(), agent, AUDIENCE, NONCE, NOW);
    expect((await verifier().verifyBound(unbound, NONCE)).has("KEY_BINDING_INVALID")).toBe(true);
    expect((await verifier({ requireKeyBinding: true }).verify(await issue())).has("KEY_NOT_BOUND")).toBe(true);
  });
});

describe("matchTrackRecord", () => {
  const proof = vector("agent-track-record.json").indexProof;
  const index = () => verifyIndexProof(proof, { pinnedPublicKey: LEDGER_KEY.publicKey.encoded });

  it("finds no mismatch for the matching proof", async () => {
    expect(matchTrackRecord(claims(), proof, await index())).toEqual([]);
  });

  it("finds a tampered total", async () => {
    expect(matchTrackRecord(claims({ total: 3 }), proof, await index()))
      .toEqual(["The track record claims 3 entries but the index proves 2"]);
    expect(matchTrackRecord(claims({ lastSequence: 3 }), proof, await index()))
      .toEqual(["The track record claims 2 entries but the index proves 2"]);
  });

  it("finds a proof for another agent or checkpoint", async () => {
    expect(matchTrackRecord(claims({ agentId: "sales-agent" }), proof, await index()))
      .toEqual(["The index proof is not about the agent sales-agent"]);
    const other = vector("index-proof.json").proofs[1];
    const check = await verifyIndexProof(other, { pinnedPublicKey: LEDGER_KEY.publicKey.encoded });
    expect(matchTrackRecord(claims(), other, check)).toContain("The index proof is not about the agent invoice-agent");
    expect(matchTrackRecord(claims({ checkpointSequence: 4 }), proof, await index()))
      .toEqual(["The index proof is for another checkpoint or index root than the track record"]);
  });
});
