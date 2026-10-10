import { equal, fromBase64, fromHex } from "./bytes.js";
import { EvidenceStatement, LogReceipt } from "./cose.js";
import {
  ChainVerifier,
  Checkpoint,
  ComplianceProfileRef,
  EvidenceLink,
  GENESIS,
  Json,
  contentHash,
  long,
  nullable,
  required,
} from "./evidence.js";
import { KeyRevocation, KeyRotation, PublicKey, revokedKeys, trustedAndRevoked } from "./keys.js";
import { leafHash, verifyInclusion } from "./merkle.js";
import { ED25519, LogCheckpoint, Note, NoteKey } from "./note.js";

export const PACKAGE_FORMAT = "nexusphere-ledger/package/v1";

export interface PackageReport {
  valid: boolean;
  keyId: string | null;
  pinnedKeyId: string | null;
  anchorSequence: number | null;
  checkpointSequence: number;
  checkpointCreatedAt: string | null;
  complianceProfiles: ComplianceProfileRef[];
  firstSequence: number;
  checkedLinks: number;
  disclosedEntries: number;
  agentId: string | null;
  principalId: string | null;
  logOrigin: string | null;
  logTreeSize: number | null;
  provenEntries: number;
  receiptedEntries: number;
  witnesses: string[];
  revokedKeys: string[];
  problems: string[];
}

export interface PackageOptions {
  witnesses?: (string | NoteKey)[];
  requiredWitnesses?: number;
  keys?: Json[];
}

interface LogResult {
  origin: string | null;
  size: number | null;
  proven: number;
  receipted: number;
  witnesses: string[];
  cosignedAt: Map<string, number>;
  signers: string[];
}

const NO_LOG: LogResult = {
  origin: null,
  size: null,
  proven: 0,
  receipted: 0,
  witnesses: [],
  cosignedAt: new Map(),
  signers: [],
};

export async function verifyPackage(
  pkg: Json,
  publicKey: string | null = null,
  options: PackageOptions = {},
): Promise<PackageReport> {
  const witnessKeys = await Promise.all(
    (options.witnesses ?? []).map((key) => (typeof key === "string" ? NoteKey.parse(key) : key)),
  );
  const requiredWitnesses = options.requiredWitnesses ?? witnessKeys.length;
  const problems: string[] = [];
  const scope = isObject(pkg.scope) ? (pkg.scope as Json) : {};
  const empty = (): PackageReport => report(false, null, null, null, 0, null, null, 0, 0, 0, scope, NO_LOG, [], problems);
  if (pkg.format !== PACKAGE_FORMAT) {
    problems.push(`unknown package format ${pkg.format ?? "null"}`);
    return empty();
  }
  let checkpoint: Checkpoint;
  let anchor: Checkpoint | null;
  try {
    if (!isObject(pkg.checkpoint)) {
      throw new Error("checkpoint is missing");
    }
    checkpoint = Checkpoint.of(pkg.checkpoint as Json);
    anchor = isObject(pkg.anchor) ? Checkpoint.of(pkg.anchor as Json) : null;
  } catch (e) {
    problems.push(`the checkpoints cannot be read: ${(e as Error).message}`);
    return empty();
  }
  const found = await readKeys(pkg, options.keys ?? [], publicKey, problems);
  const keys = found ? found.trusted : null;
  const pinned = publicKey !== null;
  const key = keys && keyFor(keys, checkpoint, pinned, problems);
  if (key && !(await checkpoint.verify(key))) {
    problems.push(`the signature of checkpoint ${checkpoint.sequence} is not valid`);
  }
  const anchorKey = keys && anchor ? keyFor(keys, anchor, pinned, problems) : null;
  if (anchor && anchorKey && !(await anchor.verify(anchorKey))) {
    problems.push(`the signature of anchor checkpoint ${anchor.sequence} is not valid`);
  }
  const first = anchor ? anchor.sequence + 1 : 1;
  const chain = new ChainVerifier(first, anchor ? anchor.headHash : GENESIS);
  let disclosed = 0;
  const agentId = nullable(scope, "agentId");
  const principalId = nullable(scope, "principalId");
  const low = long(scope.fromSequence, 1);
  const high = long(scope.toSequence, Number.MAX_SAFE_INTEGER);
  let unreadable = false;
  for (const node of Array.isArray(pkg.links) ? (pkg.links as Json[]) : []) {
    let link: EvidenceLink;
    try {
      if (!isObject(node)) {
        throw new Error("an object is missing");
      }
      link = EvidenceLink.of(node);
    } catch (e) {
      problems.push(`a link cannot be read: ${(e as Error).message}`);
      unreadable = true;
      break;
    }
    if (isObject(node.entry)) {
      disclosed++;
      const mismatch = await disclosedMismatch(node.entry as Json, link, agentId, principalId, low, high);
      if (mismatch) {
        problems.push(`sequence ${link.sequence}: ${mismatch}`);
        break;
      }
    }
    if (!(await chain.accept(link))) {
      break;
    }
  }
  const result = chain.result();
  if (!result.valid) {
    problems.push(`sequence ${result.failedSequence}: ${result.failure}`);
  } else if (
    problems.length === 0 &&
    (result.lastSequence !== checkpoint.sequence || result.lastHash !== checkpoint.headHash)
  ) {
    problems.push(
      `the chain ends at sequence ${result.lastSequence} and does not reach the signed head of checkpoint ${checkpoint.sequence}`,
    );
  }
  if (disclosed === 0 && !unreadable) {
    problems.push("the package discloses no evidence");
  }
  const log = keys
    ? await verifyLog(pkg, [...keys.values()], checkpoint.sequence, witnessKeys, requiredWitnesses, problems)
    : NO_LOG;
  const used = [checkpoint.keyId, ...(anchor ? [anchor.keyId] : []), ...log.signers];
  const revoked = found
    ? revokedUsed(found.revoked, used, log, Math.max(1, requiredWitnesses), problems)
    : [];
  return report(
    problems.length === 0,
    checkpoint.keyId,
    await pinnedKeyId(publicKey),
    anchor ? anchor.sequence : null,
    checkpoint.sequence,
    checkpoint.createdAt,
    checkpoint.profiles,
    first,
    result.checkedEntries,
    disclosed,
    scope,
    log,
    revoked,
    problems,
  );
}

function revokedUsed(
  revoked: Map<string, KeyRevocation>,
  used: string[],
  log: LogResult,
  requiredWitnesses: number,
  problems: string[],
): string[] {
  const found: string[] = [];
  for (const keyId of new Set(used)) {
    const revocation = revoked.get(keyId);
    if (!revocation) {
      continue;
    }
    found.push(keyId);
    const before = [...log.cosignedAt.values()].filter((time) => time < revocation.compromisedEpochSecond).length;
    if (before < requiredWitnesses) {
      problems.push(
        `key ${keyId} was revoked as compromised from ${javaInstant(revocation.compromisedAt)}, and ${before} of the ${requiredWitnesses} required witness cosignatures prove that the log checkpoint was made before then`,
      );
    }
  }
  return found;
}

function javaInstant(text: string): string {
  const [head, rest] = text.replace(/Z$/, "").split(".");
  let fraction = (rest ?? "").replace(/0+$/, "");
  if (fraction) {
    fraction = fraction.padEnd(3 * Math.ceil(fraction.length / 3), "0");
  }
  return head + (fraction ? "." + fraction : "") + "Z";
}

async function verifyLog(
  pkg: Json,
  keys: PublicKey[],
  size: number,
  witnessKeys: NoteKey[],
  requiredWitnesses: number,
  problems: string[],
): Promise<LogResult> {
  const log = pkg.log;
  if (!isObject(log)) {
    if (requiredWitnesses > 0) {
      problems.push("the package has no log checkpoint for witnesses to cosign");
    }
    return NO_LOG;
  }
  let note: Note;
  try {
    note = Note.parse(required(log, "checkpoint"));
  } catch (e) {
    problems.push(`the log checkpoint cannot be read: ${(e as Error).message}`);
    return NO_LOG;
  }
  const checkpoint = note.checkpoint;
  const signers: string[] = [];
  for (const key of keys) {
    if (await note.signedBy(await NoteKey.of(checkpoint.origin, ED25519, key))) {
      signers.push(key.keyId);
    }
  }
  if (signers.length === 0) {
    problems.push("the log checkpoint is not signed by a trusted ledger key");
  }
  if (checkpoint.size !== size) {
    problems.push(`the log checkpoint covers ${checkpoint.size} entries, not the ${size} of the signed checkpoint`);
  }
  const proofs = new Map<number, Uint8Array[] | null>();
  const scitt = new Map<number, Json>();
  for (const proof of Array.isArray((log as Json).proofs) ? ((log as Json).proofs as Json[]) : []) {
    const sequence = long(proof.sequence);
    try {
      proofs.set(sequence, ((proof.hashes as string[]) ?? []).map((hash) => fromBase64(hash)));
    } catch {
      proofs.set(sequence, null);
    }
    scitt.set(sequence, proof);
  }
  let proven = 0;
  let receipted = 0;
  for (const node of Array.isArray(pkg.links) ? (pkg.links as Json[]) : []) {
    if (!isObject(node) || !isObject(node.entry)) {
      continue;
    }
    const sequence = long(node.sequence);
    const hashes = proofs.get(sequence);
    let link: EvidenceLink;
    let leaf: Uint8Array;
    try {
      link = EvidenceLink.of(node);
      leaf = await leafHash(fromHex(link.hash));
    } catch {
      problems.push(`sequence ${sequence} is not proven to be in the log checkpoint`);
      continue;
    }
    if (!hashes || !(await verifyInclusion(leaf, sequence - 1, checkpoint.size, hashes, checkpoint.root))) {
      problems.push(`sequence ${sequence} is not proven to be in the log checkpoint`);
    } else {
      proven++;
    }
    const statement = scitt.get(sequence);
    if (statement && "statement" in statement) {
      const problem = await scittProblem(statement, link, keys, checkpoint, signers);
      if (problem === null) {
        receipted++;
      } else {
        problems.push(`sequence ${sequence} ${problem}`);
      }
    }
  }
  const cosignedAt = new Map<string, number>();
  for (const key of witnessKeys) {
    const time = await note.cosignedBy(key);
    if (time !== null && !cosignedAt.has(key.name)) {
      cosignedAt.set(key.name, time);
    }
  }
  const cosigned = [...cosignedAt.keys()];
  if (cosigned.length < requiredWitnesses) {
    problems.push(`the log checkpoint is cosigned by ${cosigned.length} of the ${requiredWitnesses} required witnesses`);
  }
  return {
    origin: checkpoint.origin,
    size: checkpoint.size,
    proven,
    receipted,
    witnesses: cosigned,
    cosignedAt,
    signers: [...new Set(signers)],
  };
}

async function scittProblem(
  signed: Json,
  link: EvidenceLink,
  keys: PublicKey[],
  checkpoint: LogCheckpoint,
  signers: string[],
): Promise<string | null> {
  let statement: EvidenceStatement;
  let receipt: LogReceipt;
  try {
    statement = EvidenceStatement.parse(fromBase64(required(signed, "statement")));
    receipt = LogReceipt.parse(fromBase64(required(signed, "receipt")));
  } catch (e) {
    return `has a statement or receipt that cannot be read: ${(e as Error).message}`;
  }
  const statementKey = keys.find((key) => key.keyId === statement.keyId);
  const receiptKey = keys.find((key) => key.keyId === receipt.keyId);
  if (!statementKey || !(await statement.verify(statementKey))) {
    return "has a statement that is not signed by a trusted ledger key";
  }
  signers.push(statementKey.keyId);
  if (!statement.describes(link)) {
    return "has a statement for different evidence";
  }
  const leaf = await statement.leafHash();
  if (!receiptKey || !(await receipt.verify(leaf, receiptKey))) {
    return "has a receipt that does not prove its statement with a trusted ledger key";
  }
  signers.push(receiptKey.keyId);
  if (receipt.treeSize !== checkpoint.size || !equal(await receipt.root(leaf), checkpoint.root)) {
    return "has a receipt for a different log checkpoint";
  }
  return null;
}

async function disclosedMismatch(
  entry: Json,
  link: EvidenceLink,
  agentId: string | null,
  principalId: string | null,
  low: number,
  high: number,
): Promise<string | null> {
  let sequence: number;
  let previousHash: string;
  let hash: string;
  let computed: string;
  try {
    sequence = long(entry.sequence);
    previousHash = required(entry, "previousHash");
    hash = required(entry, "hash");
    computed = await contentHash(entry);
  } catch {
    return "the disclosed entry cannot be read";
  }
  if (sequence !== link.sequence || previousHash !== link.previousHash || hash !== link.hash) {
    return "the disclosed entry does not belong to its link";
  }
  if (computed !== link.contentHash) {
    return "the disclosed entry does not match its content hash";
  }
  if (
    sequence < low ||
    sequence > high ||
    (agentId !== null && agentId !== nullable(entry, "agentId")) ||
    (principalId !== null && principalId !== nullable(entry, "principalId"))
  ) {
    return "the disclosed entry is outside the scope of the package";
  }
  return null;
}

async function readKeys(
  pkg: Json,
  keyList: Json[],
  pinned: string | null,
  problems: string[],
): Promise<{ trusted: Map<string, PublicKey>; revoked: Map<string, KeyRevocation> } | null> {
  const listed = new Map<string, PublicKey>();
  const rotations: KeyRotation[] = [];
  const revocations: KeyRevocation[] = [];
  try {
    for (const node of [...(Array.isArray(pkg.keys) ? (pkg.keys as Json[]) : []), ...keyList]) {
      const keyId = required(node, "keyId");
      const key = await PublicKey.fromBase64(required(node, "publicKey"));
      if (key.keyId !== keyId) {
        problems.push(`the key ${keyId} in the package does not match its key ID`);
        return null;
      }
      listed.set(keyId, key);
      const rotation = node.rotation;
      if (isObject(rotation)) {
        rotations.push(
          new KeyRotation({
            keyId,
            publicKey: key.encoded,
            previousKeyId: required(rotation, "previousKeyId"),
            activatedAt: required(node, "activatedAt"),
            keySignature: required(rotation, "keySignature"),
            previousKeySignature: nullable(rotation, "previousKeySignature"),
          }),
        );
      }
      const revocation = node.revocation;
      if (isObject(revocation)) {
        revocations.push(
          new KeyRevocation({
            keyId,
            compromisedAt: required(revocation, "compromisedAt"),
            revokedAt: required(revocation, "revokedAt"),
            reason: required(revocation, "reason"),
            revokerKeyId: required(revocation, "revokerKeyId"),
            signature: required(revocation, "signature"),
          }),
        );
      }
    }
    if (pinned === null) {
      return { trusted: listed, revoked: await revokedKeys(listed, revocations) };
    }
    return await trustedAndRevoked(await PublicKey.fromBase64(pinned), listed.values(), rotations, revocations);
  } catch (e) {
    problems.push(`the public keys cannot be read: ${(e as Error).message}`);
    return null;
  }
}

function keyFor(
  keys: Map<string, PublicKey>,
  checkpoint: Checkpoint,
  pinned: boolean,
  problems: string[],
): PublicKey | null {
  const key = keys.get(checkpoint.keyId);
  if (!key) {
    problems.push(
      pinned
        ? `checkpoint ${checkpoint.sequence} is signed with key ${checkpoint.keyId}, which the pinned key does not reach through signed key rotations`
        : `the package has no key ${checkpoint.keyId}`,
    );
  }
  return key ?? null;
}

async function pinnedKeyId(pinned: string | null): Promise<string | null> {
  if (pinned === null) {
    return null;
  }
  try {
    return (await PublicKey.fromBase64(pinned)).keyId;
  } catch {
    return null;
  }
}

function report(
  valid: boolean,
  keyId: string | null,
  pinnedKeyId: string | null,
  anchorSequence: number | null,
  checkpointSequence: number,
  checkpointCreatedAt: string | null,
  profiles: ComplianceProfileRef[] | null,
  firstSequence: number,
  checkedLinks: number,
  disclosedEntries: number,
  scope: Json,
  log: LogResult,
  revokedKeys: string[],
  problems: string[],
): PackageReport {
  return {
    valid,
    keyId,
    pinnedKeyId,
    anchorSequence,
    checkpointSequence,
    checkpointCreatedAt,
    complianceProfiles: (profiles ?? []).map((p) => ({ id: p.id, digest: p.digest })),
    firstSequence,
    checkedLinks,
    disclosedEntries,
    agentId: nullable(scope, "agentId"),
    principalId: nullable(scope, "principalId"),
    logOrigin: log.origin,
    logTreeSize: log.size,
    provenEntries: log.proven,
    receiptedEntries: log.receipted,
    witnesses: [...log.witnesses],
    revokedKeys: [...revokedKeys],
    problems: [...problems],
  };
}

function isObject(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
