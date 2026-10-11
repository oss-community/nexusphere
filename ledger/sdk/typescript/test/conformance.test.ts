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
  TimestampToken,
  canonicalContent,
  canonicalJson,
  commitments,
  contentHash,
  cosign,
  fromHex,
  jwkPublicKey,
  linkHash,
  mandatePayload,
  presentBound,
  sdHash,
  revocationBytes,
  rotationBytes,
  readCertificates,
  trustedAndRevoked,
  merkle,
  sha256Hex,
  toBase64,
  toHex,
  utf8,
  verifyTimestamp,
  verifyRekorEntry,
  rekorRequest,
  fromBase64,
  sha256,
  entriesDigest,
  fromUtf8,
  reportSignedBytes,
  verifyReport,
  indexSignedBytes,
  verifyIndexProof,
  TrackRecordVerifier,
  matchTrackRecord,
  trackRecordClaims,
  trackRecordPayload,
  reconcile,
  verifyStatement,
} from "../src/index.js";
import { ISSUER, LEDGER_KEY, ORIGIN, WITNESS, WITNESS_KEY, buildPackage, vector } from "./support.js";

function chainEntries(): Json[] {
  return vector("evidence-chain.json").entries.map((item: any) => {
    const { commitments: _, ...content } = item.content;
    return {
      ...content,
      ...item.values,
      salts: item.salts,
      sequence: item.sequence,
      previousHash: item.previousHash,
      hash: item.hash,
    };
  });
}

function erasedEntries(): Json[] {
  return vector("evidence-chain.json").entries.map((item: any) => ({
    ...item.content,
    salts: null,
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
      expect(canonicalJson(await canonicalContent(entries[i]))).toBe(item.canonicalContent);
      expect(await commitments(entries[i])).toEqual(item.content.commitments);
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

  it("signs the active compliance profiles in a checkpoint", async () => {
    const v = vector("compliance-checkpoint.json");
    expect(canonicalJson(v.profile)).toBe(v.canonicalProfile);
    expect(await sha256Hex(canonicalJson(v.profile))).toBe(v.profileDigest);
    const checkpoint = Checkpoint.of(v.checkpoint);
    expect(checkpoint.format).toBe(v.checkpoint.format);
    expect(new TextDecoder().decode(checkpoint.signedBytes())).toBe(v.checkpoint.signedBytes);
    expect((await checkpoint.sign(LEDGER_KEY)).signature).toBe(v.checkpoint.signature);
    expect(await checkpoint.verify(LEDGER_KEY.publicKey)).toBe(true);
    const changed = Checkpoint.of({ ...v.checkpoint, profiles: v.checkpoint.profiles.slice(1) });
    expect(await changed.verify(LEDGER_KEY.publicKey)).toBe(false);
  });

  it("breaks the chain when content changes", async () => {
    const entries = chainEntries();
    entries[1].target = "delete_invoice";
    const verifier = new ChainVerifier();
    expect(await verifier.accept(entries[0])).toBe(true);
    expect(await verifier.accept(entries[1])).toBe(false);
    expect(verifier.result()).toMatchObject({ failedSequence: 2, failure: "content does not match its hash" });
  });

  it("keeps the hashes of erased entries", async () => {
    const v = vector("evidence-chain.json");
    const verifier = new ChainVerifier();
    const entries = erasedEntries();
    for (let i = 0; i < entries.length; i++) {
      expect(await contentHash(entries[i])).toBe(v.entries[i].contentHash);
      expect(await verifier.accept(entries[i])).toBe(true);
    }
    expect(verifier.result()).toMatchObject({ valid: true, checkedEntries: 3 });
  });
});

describe("evidence-model.json", () => {
  function modelEntry(v: any): Json {
    const { commitments: _, ...content } = v.content;
    return { ...content, ...v.values, salts: v.salts, sequence: 1, previousHash: v.previousHash, hash: v.hash };
  }

  it("binds the model into the content hash", async () => {
    const v = vector("evidence-model.json");
    const entry = modelEntry(v);
    expect(canonicalJson(await canonicalContent(entry))).toBe(v.canonicalContent);
    expect(await commitments(entry)).toEqual(v.content.commitments);
    expect(await contentHash(entry)).toBe(v.contentHash);
    expect(await linkHash(1, v.previousHash, v.contentHash)).toBe(v.hash);
    expect(await new ChainVerifier().accept(entry)).toBe(true);
    for (const prompt of v.prompts) {
      expect(await sha256Hex(prompt.text)).toBe(prompt.hash);
    }
    expect([v.model.promptHash, v.model.configHash]).toEqual(v.prompts.map((p: any) => p.hash));
  });

  it("hashes an entry without a model as before", async () => {
    const v = vector("evidence-model.json");
    const { model: _, ...unnamed } = modelEntry(v);
    expect(await canonicalContent(unnamed)).not.toHaveProperty("model");
    expect(await canonicalContent({ ...unnamed, model: { name: null } })).not.toHaveProperty("model");
    expect(await contentHash(unnamed)).not.toBe(v.contentHash);
    expect(await contentHash({ ...unnamed, model: null })).toBe(await contentHash(unnamed));
  });

  it("keeps only the given model fields and requires lowercase hashes", async () => {
    const v = vector("evidence-model.json");
    const entry = modelEntry(v);
    const named = { ...entry, model: { name: v.model.name, version: null } };
    expect((await canonicalContent(named)).model).toEqual({ name: v.model.name });
    const changed = { ...entry, model: { ...v.model, version: "2026-09-02" } };
    expect(await contentHash(changed)).not.toBe(v.contentHash);
    const upper = { ...entry, model: { ...v.model, promptHash: v.model.promptHash.toUpperCase() } };
    await expect(contentHash(upper)).rejects.toThrow("promptHash must be a lowercase hex SHA-256 digest");
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

describe("mandate-key-binding.json", () => {
  const verifier = (at: number) =>
    new MandateVerifier(ISSUER, {
      keys: StaticKeys.fixed(ISSUER, LEDGER_KEY.publicKey),
      skipStatus: true,
      audience: "https://supplier.example",
      clock: () => at,
    });

  it("checks the key-bound presentation", async () => {
    const v = vector("mandate-key-binding.json");
    const check = await verifier(v.presentedAt + 10).verifyBound(v.presentation, v.nonce, "a2a/send", "supplier/orders");
    expect(check.problems).toEqual([]);
    expect(mandatePayload(check.claims!)).toEqual(v.claims);
    expect((await PublicKey.fromBase64(v.agentPublicKey)).raw).toEqual(
      (await jwkPublicKey(check.claims!.holderKey!)).raw);
    expect(await sdHash(v.token)).toBe(v.sdHash);
    expect((await verifier(v.presentedAt + 10).verifyBound(v.token, v.nonce)).has("KEY_BINDING_MISSING")).toBe(true);
    expect((await verifier(v.presentedAt + 10).verifyBound(v.presentation, "00".repeat(32))).has("KEY_BINDING_INVALID"))
      .toBe(true);
  });

  it("makes the same presentation", async () => {
    const v = vector("mandate-key-binding.json");
    const agent = await PrivateKey.fromSeed(fromHex(v.agentSeed));
    expect(await presentBound(v.token, agent, v.audience, v.nonce, v.presentedAt)).toBe(v.presentation);
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

describe("timestamp.json", () => {
  it("verifies the token against the authority's chain", async () => {
    const v = vector("timestamp.json");
    const imprint = await sha256(utf8(v.signedBytes));
    expect(toHex(imprint)).toBe(v.messageImprint);
    const chain = (v.certificates as string[]).flatMap((pem) => readCertificates(pem));
    expect(chain.length).toBe(3);
    const token = TimestampToken.parse(v.token);
    expect(toHex(TimestampToken.fromResponse(fromBase64(v.response)).encoded)).toBe(toHex(token.encoded));
    expect(token.imprintHex).toBe(v.messageImprint);
    const pinned = await verifyTimestamp(token, imprint, chain.slice(1, 3));
    expect(pinned).toEqual({ valid: true, genTime: v.genTime, authority: v.authority, pinned: true, problem: null });
    expect((await verifyTimestamp(v.token, imprint, chain)).valid).toBe(true);
    const unpinned = await verifyTimestamp(token, imprint);
    expect(unpinned).toMatchObject({ valid: true, pinned: false, authority: v.authority });
    expect((await verifyTimestamp(token, imprint, chain.slice(2, 3))).problem).toBe(
      "the timestamp authority is not trusted",
    );
    const other = imprint.slice();
    other[0] ^= 1;
    expect((await verifyTimestamp(token, other, chain)).problem).toBe("the timestamp is for different data");
    const changed = token.encoded.slice();
    changed[changed.length - 5] ^= 1;
    expect((await verifyTimestamp(changed, imprint, chain)).valid).toBe(false);
  });
});

describe("rekor.json", () => {
  it("verifies the hashedrekord entry and its log checkpoint", async () => {
    const v = vector("rekor.json");
    const ledger = await PublicKey.fromBase64(vector("keys.json").ledgerPublicKey);
    const rekorKey = await NoteKey.parse(v.rekorVerifierKey);
    expect(await rekorRequest(v.signedBytes, fromBase64(v.signature), ledger)).toBe(v.request);
    const pinned = await verifyRekorEntry(v.entry, v.signedBytes, ledger, [v.rekorVerifierKey]);
    expect(pinned).toEqual({ valid: true, logIndex: Number(v.entry.logIndex), origin: v.origin, pinned: true, problem: null });
    expect(v.origin).toBe("rekor.local");
    expect((await verifyRekorEntry(v.entry, utf8(v.signedBytes), ledger, [rekorKey])).valid).toBe(true);
    expect(await verifyRekorEntry(v.entry, v.signedBytes, ledger)).toMatchObject({ valid: true, pinned: false, origin: v.origin });
    const numeric = structuredClone(v.entry);
    numeric.logIndex = Number(numeric.logIndex);
    numeric.inclusionProof.logIndex = Number(numeric.inclusionProof.logIndex);
    numeric.inclusionProof.treeSize = Number(numeric.inclusionProof.treeSize);
    expect((await verifyRekorEntry(numeric, v.signedBytes, ledger, [rekorKey])).valid).toBe(true);
    const other = await NoteKey.of(v.origin, ED25519, WITNESS_KEY.publicKey);
    const problem = async (entry: unknown, signedBytes: string = v.signedBytes, key = ledger) =>
      (await verifyRekorEntry(entry, signedBytes, key, [other])).problem;
    expect(await problem(v.entry)).toBe("the log checkpoint is not signed by a pinned Rekor key");
    expect(await problem(v.entry, v.signedBytes + " ")).toBe("the entry is for different data");
    expect(await problem(v.entry, v.signedBytes, WITNESS_KEY.publicKey)).toBe("the entry is signed by another key");
    const hash = structuredClone(v.entry);
    const changed = fromBase64(hash.inclusionProof.hashes[0]);
    changed[0] ^= 1;
    hash.inclusionProof.hashes[0] = toBase64(changed);
    expect(await problem(hash)).toBe("the entry is not proven in the log");
    const root = structuredClone(v.entry);
    const rootHash = fromBase64(root.inclusionProof.rootHash);
    rootHash[0] ^= 1;
    root.inclusionProof.rootHash = toBase64(rootHash);
    expect(await problem(root)).toBe("the entry is not proven in the log");
    const size = structuredClone(v.entry);
    size.inclusionProof.checkpoint.envelope = size.inclusionProof.checkpoint.envelope.replace("\n2\n", "\n3\n");
    expect(await problem(size)).toBe("the log checkpoint does not match the inclusion proof");
    const index = structuredClone(v.entry);
    index.inclusionProof.logIndex = "0";
    expect(await problem(index)).toBe("the inclusion proof is for another entry");
    const missing = structuredClone(v.entry);
    delete missing.logIndex;
    expect(await problem(missing)).toBe("the entry cannot be read: logIndex is missing");
    const kind = structuredClone(v.entry);
    const body = JSON.parse(new TextDecoder().decode(fromBase64(kind.canonicalizedBody)));
    kind.canonicalizedBody = toBase64(utf8(JSON.stringify({ ...body, apiVersion: "0.0.1" })));
    expect(await problem(kind)).toBe("the entry is not a hashedrekord 0.0.2");
  });
});

describe("report.json", () => {
  it("rebuilds the signed bytes, the signature and the entries digest", async () => {
    const v = vector("report.json");
    const bytes = reportSignedBytes(v.report);
    expect(fromUtf8(bytes)).toBe(v.signedBytes);
    expect(v.report.keyId).toBe(LEDGER_KEY.keyId);
    expect(await LEDGER_KEY.publicKey.verifyBase64(bytes, v.report.signature)).toBe(true);
    expect(await LEDGER_KEY.signBase64(bytes)).toBe(v.report.signature);
    expect(await entriesDigest(v.entryHashes)).toBe(v.entriesDigest);
    expect(v.report.evidence.entriesDigest).toBe(v.entriesDigest);
    const chain = chainEntries();
    expect(chain.map((entry) => entry.hash)).toEqual(v.entryHashes);
    const pkg = await buildPackage(chain, [1, 2, 3]);
    const check = await verifyReport(v.report, pkg, { publicKey: LEDGER_KEY.publicKey.encoded });
    expect(check).toEqual({
      valid: true,
      report: "dispute",
      title: "Dispute package",
      keyId: LEDGER_KEY.keyId,
      checkpointSequence: 3,
      entries: 3,
      statuses: { PARTLY_SHOWN: 1, SHOWN: 1 },
      problems: [],
    });
  });
});

describe("index-proof.json", () => {
  it("rebuilds the signed bytes and verifies every proof against the ledger key", async () => {
    const v = vector("index-proof.json");
    expect(fromUtf8(indexSignedBytes(v.proofs[0].index))).toBe(v.signedBytes);
    expect(await LEDGER_KEY.signBase64(utf8(v.signedBytes))).toBe(v.proofs[0].index.signature);
    for (const proof of v.proofs) {
      const check = await verifyIndexProof(proof, { pinnedPublicKey: LEDGER_KEY.publicKey.encoded });
      expect(check.problems).toEqual([]);
      expect(check.valid).toBe(true);
      expect(check.term).toEqual(proof.term);
      expect(check.present).toBe(proof.leaf !== null);
      expect(check.sequence).toBe(3);
      expect(check.keyId).toBe(LEDGER_KEY.keyId);
    }
    expect(await verifyIndexProof(v.proofs[0], { pinnedPublicKey: LEDGER_KEY.publicKey.encoded })).toEqual({
      valid: true,
      term: { type: "agent", agentId: "invoice-agent" },
      present: true,
      count: 2,
      firstSequence: 1,
      lastSequence: 2,
      sequence: 3,
      headHash: v.proofs[0].index.headHash,
      keyId: LEDGER_KEY.keyId,
      problems: [],
    });
    expect(v.proofs.slice(2).map((proof: Json) => proof.leaf)).toEqual([null, null]);
  });
});

describe("agent-track-record.json", () => {
  const verifier = () =>
    new TrackRecordVerifier(ISSUER, {
      keys: StaticKeys.fixed(ISSUER, LEDGER_KEY.publicKey),
      clock: () => 1790841600 + 3600,
    });

  it("checks the track record, its outcomes-only presentation and its index proof", async () => {
    const v = vector("agent-track-record.json");
    const full = await verifier().verify(v.token);
    const presented = await verifier().verify(v.presentedWithOutcomesOnly);
    const index = await verifyIndexProof(v.indexProof, { pinnedPublicKey: LEDGER_KEY.publicKey.encoded });
    expect(full.problems).toEqual([]);
    expect(full.claims).toEqual(trackRecordClaims(v.claims));
    expect(trackRecordPayload(full.claims!)).toEqual(v.claims);
    expect(presented.problems).toEqual([]);
    expect(presented.claims!.actions).toBeNull();
    expect(presented.claims!.principals).toBeNull();
    expect(presented.claims!.outcomes).toEqual(full.claims!.outcomes);
    expect(index.problems).toEqual([]);
    expect(matchTrackRecord(full.claims!, v.indexProof, index)).toEqual([]);
  });
});

describe("a2a-reconciliation.json", () => {
  it("verifies both statements and reconciles them as stored", async () => {
    const v = vector("a2a-reconciliation.json");
    const oursKey = await PublicKey.fromBase64(v.oursPublicKey);
    const theirsKey = await PublicKey.fromBase64(v.theirsPublicKey);
    const ours = await verifyStatement(v.ours, StaticKeys.fixed("https://ledger.example", oursKey));
    const theirs = await verifyStatement(v.theirs, StaticKeys.fixed("https://supplier.example", theirsKey));
    const r = reconcile(ours, theirs);
    const json = JSON.parse(JSON.stringify(r));
    expect(json.ours).toBe(v.reconciliation.ours);
    expect(json.theirs).toBe(v.reconciliation.theirs);
    expect(json.from).toBe(v.reconciliation.from);
    expect(json.to).toBe(v.reconciliation.to);
    expect(r.matched).toBe(v.reconciliation.matched);
    expect(r.undelivered).toBe(v.reconciliation.undelivered);
    expect(json.discrepancies).toEqual(v.reconciliation.discrepancies);
    expect(json).toEqual(v.reconciliation);
    await expect(verifyStatement(v.ours, StaticKeys.fixed("https://ledger.example", theirsKey))).rejects.toThrow();
  });
});
