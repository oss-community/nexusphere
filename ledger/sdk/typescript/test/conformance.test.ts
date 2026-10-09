import { describe, expect, it } from "vitest";
import {
  COSIGNATURE,
  ChainVerifier,
  Checkpoint,
  ED25519,
  EvidenceLink,
  EvidenceStatement,
  Json,
  KeyRevocation,
  KeyRotation,
  LogCheckpoint,
  LogReceipt,
  MandateVerifier,
  Note,
  NoteKey,
  PrivateKey,
  PublicKey,
  StaticKeys,
  canonicalContent,
  canonicalJson,
  contentHash,
  cosign,
  fromHex,
  linkHash,
  mandatePayload,
  revocationBytes,
  rotationBytes,
  trustedAndRevoked,
  merkle,
  sha256Hex,
  toBase64,
  toHex,
  utf8,
} from "../src/index.js";
import { ISSUER, LEDGER_KEY, ORIGIN, WITNESS, WITNESS_KEY, vector } from "./support.js";

function chainEntries(): Json[] {
  return vector("evidence-chain.json").entries.map((item: any) => ({
    ...item.content,
    sequence: item.sequence,
    previousHash: item.previousHash,
    hash: item.hash,
  }));
}

describe("keys.json", () => {
  it("rebuilds the keys", async () => {
    const v = vector("keys.json");
    const ledger = await PrivateKey.fromSeed(fromHex(v.ledgerSeed));
    const witness = await PrivateKey.fromSeed(fromHex(v.witnessSeed));
    expect(ledger.encoded).toBe(v.ledgerPrivateKey);
    expect(ledger.publicKey.encoded).toBe(v.ledgerPublicKey);
    expect(ledger.keyId).toBe(v.ledgerKeyId);
    expect(witness.publicKey.encoded).toBe(v.witnessPublicKey);
    expect((await NoteKey.of(ORIGIN, ED25519, ledger.publicKey)).vkey).toBe(v.logVerifierKey);
    expect((await NoteKey.of(WITNESS, COSIGNATURE, witness.publicKey)).vkey).toBe(v.witnessVerifierKey);
    expect((await PrivateKey.fromBase64(v.ledgerPrivateKey)).keyId).toBe(ledger.keyId);
    const parsed = await NoteKey.parse(v.logVerifierKey);
    expect(parsed.publicKey.equals(await PublicKey.fromBase64(v.ledgerPublicKey))).toBe(true);
  });
});

describe("canonical-json.json", () => {
  it("writes canonical JSON", async () => {
    for (const item of vector("canonical-json.json")) {
      expect(canonicalJson(item.input)).toBe(item.canonical);
      expect(await sha256Hex(item.canonical)).toBe(item.sha256);
    }
  });
});

describe("evidence-chain.json", () => {
  it("rebuilds the content and link hashes", async () => {
    const v = vector("evidence-chain.json");
    const verifier = new ChainVerifier();
    const entries = chainEntries();
    for (let i = 0; i < entries.length; i++) {
      const item = v.entries[i];
      expect(canonicalJson(canonicalContent(entries[i]))).toBe(item.canonicalContent);
      expect(await contentHash(entries[i])).toBe(item.contentHash);
      expect(await linkHash(item.sequence, item.previousHash, item.contentHash)).toBe(item.hash);
      expect(await verifier.accept(entries[i])).toBe(true);
    }
    expect(verifier.result()).toMatchObject({ valid: true, checkedEntries: 3 });
  });

  it("rebuilds the checkpoint signature", async () => {
    const v = vector("evidence-chain.json");
    const checkpoint = new Checkpoint(3, v.entries[2].hash, "2026-10-01T08:01:00.123456Z", LEDGER_KEY.keyId);
    expect(new TextDecoder().decode(checkpoint.signedBytes())).toBe(v.checkpoint.signedBytes);
    const signed = await checkpoint.sign(LEDGER_KEY);
    expect(signed.signature).toBe(v.checkpoint.signature);
    expect(await signed.verify(LEDGER_KEY.publicKey)).toBe(true);
  });

  it("breaks the chain when content changes", async () => {
    const entries = chainEntries();
    entries[1].target = "delete_invoice";
    const verifier = new ChainVerifier();
    expect(await verifier.accept(entries[0])).toBe(true);
    expect(await verifier.accept(entries[1])).toBe(false);
    expect(verifier.result()).toMatchObject({ failedSequence: 2, failure: "content does not match its hash" });
  });
});

describe("merkle-tree.json", async () => {
  const v = vector("merkle-tree.json");
  const leaves = await Promise.all([0, 1, 2, 3, 4, 5, 6, 7].map((i) => merkle.leafHash(Uint8Array.of(i))));

  it("rebuilds leaves and roots", async () => {
    expect(leaves.map(toHex)).toEqual(v.leafHashes);
    for (const item of v.roots) {
      expect(toHex(await merkle.root(leaves.slice(0, item.size)))).toBe(item.root);
    }
  });

  it("rebuilds and checks inclusion proofs", async () => {
    for (const item of v.inclusionProofs) {
      const subset = leaves.slice(0, item.size);
      const proof = await merkle.inclusionProof(subset, item.index);
      expect(proof.map(toHex)).toEqual(item.proof);
      const root = await merkle.root(subset);
      expect(await merkle.verifyInclusion(leaves[item.index], item.index, item.size, proof, root)).toBe(true);
      if (proof.length) {
        expect(await merkle.verifyInclusion(leaves[item.index], item.index, item.size, proof.slice(0, -1), root)).toBe(false);
      }
    }
  });

  it("rebuilds and checks consistency proofs", async () => {
    for (const item of v.consistencyProofs) {
      const proof = await merkle.consistencyProof(leaves.slice(0, item.second), item.first);
      expect(proof.map(toHex)).toEqual(item.proof);
      const firstRoot = await merkle.root(leaves.slice(0, item.first));
      const secondRoot = await merkle.root(leaves.slice(0, item.second));
      expect(await merkle.verifyConsistency(item.first, item.second, proof, firstRoot, secondRoot)).toBe(true);
      if (item.first > 0 && item.first < item.second) {
        expect(await merkle.verifyConsistency(item.first, item.second, proof, secondRoot, secondRoot)).toBe(false);
      }
    }
  });
});

describe("signed-note.json", () => {
  it("rebuilds the note and the cosignature", async () => {
    const v = vector("signed-note.json");
    const leaves = await Promise.all(
      vector("evidence-chain.json").entries.map((e: any) => merkle.leafHash(fromHex(e.hash))),
    );
    const root = await merkle.root(leaves);
    expect(toBase64(root)).toBe(v.rootHash);
    const logKey = await NoteKey.of(ORIGIN, ED25519, LEDGER_KEY.publicKey);
    const witnessKey = await NoteKey.of(WITNESS, COSIGNATURE, WITNESS_KEY.publicKey);
    const note = await new LogCheckpoint(ORIGIN, 3, root).sign(logKey, LEDGER_KEY);
    expect(note.text()).toBe(v.note);
    const cosigned = note.withSignature(await cosign(note.body, witnessKey, WITNESS_KEY, 1760000000));
    expect(cosigned.text()).toBe(v.cosignedNote);
    const parsed = Note.parse(v.cosignedNote);
    expect(await parsed.signedBy(logKey)).toBe(true);
    expect(await parsed.cosignedBy(witnessKey)).toBe(1760000000);
    expect(await Note.parse(v.note).cosignedBy(witnessKey)).toBeNull();
    expect(await Note.parse(v.note.replace("\n3\n", "\n4\n")).signedBy(logKey)).toBe(false);
  });
});

describe("scitt.json", () => {
  it("rebuilds statements and receipts", async () => {
    const v = vector("scitt.json");
    const entries = chainEntries();
    const leaves = await Promise.all(entries.map((e) => merkle.leafHash(fromHex(e.hash as string))));
    const root = await merkle.root(leaves);
    expect(toHex(root)).toBe(v.rootHash);
    for (let i = 0; i < entries.length; i++) {
      const item = v.items[i];
      const index = item.sequence - 1;
      expect(toHex(await EvidenceStatement.sign(entries[i], ISSUER, LEDGER_KEY.keyId, LEDGER_KEY))).toBe(item.statement);
      const path = await merkle.inclusionProof(leaves, index);
      expect(toHex(await LogReceipt.sign(ORIGIN, LEDGER_KEY.keyId, entries.length, index, path, root, LEDGER_KEY))).toBe(
        item.receipt,
      );
      const statement = EvidenceStatement.parse(fromHex(item.statement));
      const receipt = LogReceipt.parse(fromHex(item.receipt));
      const leaf = await statement.leafHash();
      expect(await statement.verify(LEDGER_KEY.publicKey)).toBe(true);
      expect(statement.describes(await EvidenceLink.ofEntry(entries[i]))).toBe(true);
      expect(statement.subject).toBe("urn:uuid:" + entries[i].id);
      expect(statement.issuer).toBe(ISSUER);
      expect(await receipt.verify(leaf, LEDGER_KEY.publicKey)).toBe(true);
      expect(toHex((await receipt.root(leaf))!)).toBe(toHex(root));
      expect(await receipt.verify(await merkle.leafHash(utf8("other")), LEDGER_KEY.publicKey)).toBe(false);
      expect(await statement.verify(WITNESS_KEY.publicKey)).toBe(false);
    }
  });
});

describe("mandate.json", () => {
  const verifier = () =>
    new MandateVerifier(ISSUER, {
      keys: StaticKeys.fixed(ISSUER, LEDGER_KEY.publicKey),
      skipStatus: true,
      clock: () => 1790841600 + 3600,
    });

  it("checks the full token", async () => {
    const v = vector("mandate.json");
    const check = await verifier().verifyAction(v.token, "a2a/send", "supplier/sales");
    expect(check.problems).toEqual([]);
    expect(check.valid).toBe(true);
    expect(mandatePayload(check.claims!)).toEqual(v.claims);
  });

  it("checks the token presented with only the grant", async () => {
    const v = vector("mandate.json");
    const check = await verifier().verify(v.presentedWithGrantOnly);
    expect(check.valid).toBe(true);
    const payload = mandatePayload(check.claims!) as any;
    expect(payload.mandate.grant).toBe(v.claims.mandate.grant);
    expect(payload.mandate.principal).toBeUndefined();
    expect(payload.mandate.termsHash).toBeUndefined();
  });
});

describe("key-history.json", () => {
  it("rebuilds the rotation and the revocation", async () => {
    const v = vector("key-history.json");
    const rotation = await KeyRotation.issue(WITNESS_KEY, LEDGER_KEY.keyId, LEDGER_KEY, v.rotation.activatedAt);
    const revocation = await KeyRevocation.issue(
      LEDGER_KEY.keyId,
      v.revocation.compromisedAt,
      v.revocation.revokedAt,
      v.revocation.reason,
      WITNESS_KEY,
    );
    expect(
      new TextDecoder().decode(
        rotationBytes(WITNESS_KEY.keyId, WITNESS_KEY.publicKey.encoded, LEDGER_KEY.keyId, rotation.activatedAt),
      ),
    ).toBe(v.rotation.signedContent);
    expect(rotation.keySignature).toBe(v.rotation.keySignature);
    expect(rotation.previousKeySignature).toBe(v.rotation.previousKeySignature);
    expect(
      new TextDecoder().decode(
        revocationBytes(LEDGER_KEY.keyId, revocation.compromisedAt, revocation.revokedAt, revocation.reason,
          WITNESS_KEY.keyId),
      ),
    ).toBe(v.revocation.signedContent);
    expect(revocation.signature).toBe(v.revocation.signature);
    expect(revocation.compromisedEpochSecond).toBe(v.compromisedEpochSecond);
    const { trusted, revoked } = await trustedAndRevoked(WITNESS_KEY.publicKey, [LEDGER_KEY.publicKey], [rotation], [
      revocation,
    ]);
    expect(trusted.has(LEDGER_KEY.keyId)).toBe(true);
    expect(revoked.has(LEDGER_KEY.keyId)).toBe(true);
  });
});
