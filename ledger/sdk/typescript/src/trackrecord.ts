import { fromBase64Url } from "./bytes.js";
import { Json, isSha256 } from "./evidence.js";
import { IndexCheck } from "./indexproof.js";
import { PrivateKey, PublicKey } from "./keys.js";
import {
  Clock,
  Disclosure,
  Fetcher,
  HASH_ALGORITHM,
  JWS_ALGORITHM,
  Jws,
  JwksKeys,
  KeyResolver,
  MandateProblem,
  Problem,
  SdJwt,
  TYPE,
  httpFetcher,
  jwkPublicKey,
  keyBindingProblem,
} from "./mandate.js";
import { epochText, parseMicros } from "./timestamps.js";

export const TRACK_RECORD_VCT = "urn:nexusphere:vct:agent-track-record:1";
export const TRACK_RECORD_SELECTIVE = ["outcomes", "principals", "actions"];

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export interface TrackRecordClaims {
  issuer: string;
  recordId: string;
  agentId: string;
  audience: string | null;
  issuedAt: number;
  expiresAt: number;
  checkpointSequence: number;
  headHash: string;
  indexRoot: string;
  total: number;
  firstSequence: number | null;
  lastSequence: number | null;
  firstAt: string | null;
  lastAt: string | null;
  outcomes: Record<string, number> | null;
  principals: number | null;
  actions: Record<string, number> | null;
  holderKey?: Json | null;
}

function isObject(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function trackRecordClaims(p: Json): TrackRecordClaims {
  if (p.vct !== TRACK_RECORD_VCT) {
    throw new Error(`The token is not a ${TRACK_RECORD_VCT} credential`);
  }
  const r = isObject(p.record) ? p.record : {};
  const c = isObject(r.checkpoint) ? r.checkpoint : {};
  const active = Number.isInteger(r.firstSequence);
  const claims: TrackRecordClaims = {
    issuer: text(p, "iss"),
    recordId: uuid(text(p, "jti")),
    agentId: text(p, "sub"),
    audience: typeof p.aud === "string" ? p.aud : null,
    issuedAt: number(p, "iat"),
    expiresAt: number(p, "exp"),
    checkpointSequence: number(c, "sequence"),
    headHash: text(c, "headHash"),
    indexRoot: text(c, "indexRoot"),
    total: number(r, "total"),
    firstSequence: active ? number(r, "firstSequence") : null,
    lastSequence: active ? number(r, "lastSequence") : null,
    firstAt: active ? instant(r, "firstAt") : null,
    lastAt: active ? instant(r, "lastAt") : null,
    outcomes: counts(r.outcomes),
    principals: typeof r.principals === "number" ? Math.trunc(r.principals) : null,
    actions: counts(r.actions),
    holderKey: confirmation(p.cnf),
  };
  return checkedTrackRecord(claims);
}

export function checkedTrackRecord(claims: TrackRecordClaims): TrackRecordClaims {
  if (!isSha256(claims.headHash) || !isSha256(claims.indexRoot)) {
    throw new Error("The track record names no valid head hash or index root");
  }
  return { ...claims, outcomes: sorted(claims.outcomes), actions: sorted(claims.actions) };
}

export function trackRecordPayload(c: TrackRecordClaims): Json {
  const payload: Json = { iss: c.issuer, vct: TRACK_RECORD_VCT, sub: c.agentId };
  if (c.audience !== null) {
    payload.aud = c.audience;
  }
  Object.assign(payload, { jti: c.recordId, iat: c.issuedAt, nbf: c.issuedAt, exp: c.expiresAt });
  const record: Json = {
    checkpoint: { sequence: c.checkpointSequence, headHash: c.headHash, indexRoot: c.indexRoot },
    total: c.total,
  };
  if (c.firstSequence !== null) {
    Object.assign(record, { firstSequence: c.firstSequence, lastSequence: c.lastSequence, firstAt: c.firstAt,
      lastAt: c.lastAt });
  }
  if (c.outcomes !== null) {
    record.outcomes = sorted(c.outcomes);
  }
  if (c.principals !== null) {
    record.principals = c.principals;
  }
  if (c.actions !== null) {
    record.actions = sorted(c.actions);
  }
  payload.record = record;
  if (c.holderKey) {
    payload.cnf = { jwk: { ...c.holderKey } };
  }
  return payload;
}

export async function issueTrackRecord(claims: TrackRecordClaims, keyId: string, key: PrivateKey): Promise<string> {
  const payload = trackRecordPayload(checkedTrackRecord(claims));
  const record = payload.record as Json;
  const disclosures: Disclosure[] = [];
  for (const name of TRACK_RECORD_SELECTIVE) {
    if (name in record) {
      disclosures.push(Disclosure.of(name, record[name]));
      delete record[name];
    }
  }
  record._sd = (await Promise.all(disclosures.map((d) => d.digest()))).sort();
  payload._sd_alg = HASH_ALGORITHM;
  return SdJwt.issue({ alg: JWS_ALGORITHM, typ: TYPE, kid: keyId }, payload, disclosures, key);
}

export async function trackRecordOf(token: string): Promise<TrackRecordClaims> {
  return trackRecordClaims(await SdJwt.parse(token).claims());
}

export function matchTrackRecord(claims: TrackRecordClaims, proof: unknown, check: IndexCheck): string[] {
  const problems: string[] = [];
  if (check.term.type !== "agent" || check.term.agentId !== claims.agentId) {
    problems.push("The index proof is not about the agent " + claims.agentId);
  }
  const index = isObject(proof) && isObject(proof.index) ? proof.index : {};
  if (check.sequence === null || check.sequence !== claims.checkpointSequence || claims.headHash !== check.headHash
    || claims.indexRoot !== index.root) {
    problems.push("The index proof is for another checkpoint or index root than the track record");
  }
  const count = check.present ? check.count : 0;
  if (count !== claims.total || (check.present ? check.firstSequence : null) !== claims.firstSequence
    || (check.present ? check.lastSequence : null) !== claims.lastSequence) {
    problems.push(`The track record claims ${claims.total} entries but the index proves ${count}`);
  }
  return problems;
}

function sorted(counts: Record<string, number> | null): Record<string, number> | null {
  if (counts === null) {
    return null;
  }
  return Object.fromEntries(Object.entries(counts).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
}

function counts(node: unknown): Record<string, number> | null {
  if (node === undefined || node === null) {
    return null;
  }
  if (!isObject(node)) {
    throw new Error("The track record has malformed counts");
  }
  const result: Record<string, number> = {};
  for (const [name, value] of Object.entries(node)) {
    if (!Number.isInteger(value) || (value as number) < 0) {
      throw new Error("The track record has a malformed count for " + name);
    }
    result[name] = value as number;
  }
  return result;
}

function confirmation(cnf: unknown): Json | null {
  if (!isObject(cnf)) {
    return null;
  }
  const jwk = cnf.jwk;
  if (!isObject(jwk) || jwk.kty !== "OKP" || jwk.crv !== "Ed25519" || typeof jwk.x !== "string") {
    throw new Error("The track record has a cnf claim without an Ed25519 jwk");
  }
  if (fromBase64Url(jwk.x).length !== 32) {
    throw new Error("An Ed25519 key has 32 bytes");
  }
  return { kty: "OKP", crv: "Ed25519", x: jwk.x };
}

function text(node: Json, name: string): string {
  if (typeof node[name] !== "string") {
    throw new Error("The track record has no " + name);
  }
  return node[name] as string;
}

function number(node: Json, name: string): number {
  if (!Number.isInteger(node[name])) {
    throw new Error("The track record has no " + name);
  }
  return node[name] as number;
}

function instant(node: Json, name: string): string {
  const value = text(node, name);
  parseMicros(value);
  return value;
}

function uuid(value: string): string {
  if (!UUID.test(value)) {
    throw new Error("Invalid UUID string: " + value);
  }
  return value.toLowerCase();
}

export class TrackRecordCheck {
  constructor(
    readonly claims: TrackRecordClaims | null,
    readonly problems: Problem[] = [],
  ) {}

  get valid(): boolean {
    return this.claims !== null && this.problems.length === 0;
  }

  has(code: MandateProblem): boolean {
    return this.problems.some((problem) => problem.code === code);
  }
}

export interface TrackRecordVerifierOptions {
  keys?: KeyResolver;
  audience?: string | null;
  clock?: Clock;
  clockSkew?: number;
  cacheTtl?: number;
  timeoutMillis?: number;
  fetcher?: Fetcher;
  requireKeyBinding?: boolean;
  keyBindingMaxAge?: number;
}

export class TrackRecordVerifier {
  private readonly issuers: Set<string>;
  private readonly keys: KeyResolver;
  private readonly audience: string | null;
  private readonly clock: Clock;
  private readonly skew: number;
  private readonly requireKeyBinding: boolean;
  private readonly keyBindingMaxAge: number;

  constructor(issuers: string | string[], options: TrackRecordVerifierOptions = {}) {
    const list = typeof issuers === "string" ? [issuers] : issuers;
    this.issuers = new Set(list.map((issuer) => issuer.replace(/\/$/, "")));
    if (this.issuers.size === 0) {
      throw new Error("At least one trusted issuer is required");
    }
    this.clock = options.clock ?? (() => Date.now() / 1000);
    this.skew = options.clockSkew ?? 60;
    this.audience = options.audience ?? null;
    this.requireKeyBinding = options.requireKeyBinding ?? false;
    this.keyBindingMaxAge = options.keyBindingMaxAge ?? 300;
    this.keys = options.keys
      ?? new JwksKeys(options.fetcher ?? httpFetcher(options.timeoutMillis), options.cacheTtl ?? 300, this.clock);
  }

  verify(token: string): Promise<TrackRecordCheck> {
    return this.verifyBound(token, null);
  }

  async verifyBound(token: string, nonce: string | null): Promise<TrackRecordCheck> {
    let parsed: Jws;
    let claims: TrackRecordClaims;
    let sdJwt: SdJwt;
    try {
      sdJwt = SdJwt.parse(token);
      parsed = sdJwt.jwt;
      claims = trackRecordClaims(await sdJwt.claims());
    } catch (e) {
      return new TrackRecordCheck(null, [{ code: "MALFORMED", message: (e as Error).message }]);
    }
    const fail = (code: MandateProblem, message: string) => new TrackRecordCheck(claims, [{ code, message }]);
    if (parsed.header.typ !== TYPE || parsed.header.alg !== JWS_ALGORITHM) {
      return fail("WRONG_TYPE", "The token is not an EdDSA " + TYPE);
    }
    if (!this.issuers.has(claims.issuer)) {
      return fail("UNTRUSTED_ISSUER", `The issuer ${claims.issuer} is not trusted`);
    }
    const kid = typeof parsed.header.kid === "string" ? parsed.header.kid : "";
    let key: PublicKey | null;
    try {
      key = await this.keys.resolve(claims.issuer, kid);
    } catch (e) {
      return fail("UNKNOWN_KEY", `The keys of ${claims.issuer} cannot be read: ${(e as Error).message}`);
    }
    if (!key) {
      return fail("UNKNOWN_KEY", "The issuer has no key " + kid);
    }
    if (!(await parsed.verify(key))) {
      return fail("BAD_SIGNATURE", "The signature does not match the issuer's key");
    }
    const problems: Problem[] = [];
    const now = this.clock();
    if (now + this.skew < claims.issuedAt) {
      problems.push({ code: "NOT_YET_VALID", message: "The track record is valid from " + epochText(claims.issuedAt) });
    }
    if (!(now - this.skew < claims.expiresAt)) {
      problems.push({ code: "EXPIRED", message: "The track record expired at " + epochText(claims.expiresAt) });
    }
    if (this.audience !== null && claims.audience !== null && this.audience !== claims.audience) {
      problems.push({ code: "WRONG_AUDIENCE", message: `The track record is for ${claims.audience}, not ${this.audience}` });
    }
    const binding = await this.keyBinding(sdJwt, claims, nonce, now);
    if (binding) {
      problems.push(binding);
    }
    return new TrackRecordCheck(claims, problems);
  }

  private async keyBinding(sdJwt: SdJwt, claims: TrackRecordClaims, nonce: string | null, now: number) {
    if (!claims.holderKey) {
      if (sdJwt.keyBinding !== null) {
        return { code: "KEY_BINDING_INVALID", message: "The track record names no agent key, so it cannot carry a key binding" } as Problem;
      }
      return this.requireKeyBinding
        ? ({ code: "KEY_NOT_BOUND", message: "The track record is not bound to a key of the agent" } as Problem)
        : null;
    }
    if (sdJwt.keyBinding === null) {
      return { code: "KEY_BINDING_MISSING", message: "The track record is bound to a key of the agent and needs a key binding" } as Problem;
    }
    const problem = await keyBindingProblem(sdJwt, await jwkPublicKey(claims.holderKey),
      this.audience ?? claims.audience, nonce, now, this.skew, this.keyBindingMaxAge);
    return problem === null ? null : ({ code: "KEY_BINDING_INVALID", message: problem } as Problem);
  }
}
