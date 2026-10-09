import { ascii, fromBase64Url, fromUtf8, toBase64Url, utf8 } from "./bytes.js";
import { sha256 } from "./canonical.js";
import { PrivateKey, PublicKey } from "./keys.js";
import { epochText } from "./timestamps.js";

export const JWS_ALGORITHM = "EdDSA";
export const TYPE = "dc+sd-jwt";
export const VCT = "urn:nexusphere:vct:agent-mandate:1";
export const SELECTIVE = ["principal", "grant", "termsHash"];
export const HASH_ALGORITHM = "sha-256";
export const STATUS_LIST_TYPE = "statuslist+jwt";
export const KEYS_PATH = "/public/v1/keys";

export type MandateProblem =
  | "MALFORMED"
  | "WRONG_TYPE"
  | "UNTRUSTED_ISSUER"
  | "UNKNOWN_KEY"
  | "BAD_SIGNATURE"
  | "NOT_YET_VALID"
  | "EXPIRED"
  | "WRONG_AUDIENCE"
  | "NOT_COVERED"
  | "REVOKED"
  | "STATUS_UNAVAILABLE";

type Json = Record<string, unknown>;

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

function parseJson(bytes: Uint8Array): unknown {
  try {
    return JSON.parse(fromUtf8(bytes));
  } catch {
    throw new Error("The token is not valid JSON");
  }
}

function isObject(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export class Jws {
  constructor(
    readonly header: Json,
    readonly payload: Json,
    readonly signingInput: Uint8Array,
    readonly signature: string,
  ) {}

  static parse(token: string): Jws {
    if (typeof token !== "string") {
      throw new Error("The token is missing");
    }
    const parts = token.trim().split(".");
    if (parts.length !== 3) {
      throw new Error("The token is not a compact JWS");
    }
    let header: unknown;
    let payload: unknown;
    try {
      header = parseJson(fromBase64Url(parts[0]));
      payload = parseJson(fromBase64Url(parts[1]));
    } catch {
      throw new Error("The token is not valid JSON");
    }
    if (!isObject(header) || !isObject(payload)) {
      throw new Error("The token header and payload must be JSON objects");
    }
    return new Jws(header, payload, ascii(parts[0] + "." + parts[1]), parts[2]);
  }

  static async sign(header: Json, payload: Json, key: PrivateKey): Promise<string> {
    const input = toBase64Url(utf8(JSON.stringify(header))) + "." + toBase64Url(utf8(JSON.stringify(payload)));
    return input + "." + toBase64Url(await key.sign(ascii(input)));
  }

  static signTyped(type: string, keyId: string, payload: Json, key: PrivateKey): Promise<string> {
    return Jws.sign({ alg: JWS_ALGORITHM, typ: type, kid: keyId }, payload, key);
  }

  async verify(key: PublicKey): Promise<boolean> {
    let raw: Uint8Array;
    try {
      raw = fromBase64Url(this.signature);
    } catch {
      return false;
    }
    return key.verify(this.signingInput, raw);
  }
}

export class Disclosure {
  private constructor(
    readonly encoded: string,
    readonly name: string,
    readonly value: unknown,
  ) {}

  static of(name: string, value: unknown): Disclosure {
    const salt = crypto.getRandomValues(new Uint8Array(16));
    return Disclosure.parse(toBase64Url(utf8(JSON.stringify([toBase64Url(salt), name, value]))));
  }

  static parse(encoded: string): Disclosure {
    let array: unknown;
    try {
      array = parseJson(fromBase64Url(encoded));
    } catch {
      throw new Error("A disclosure is not base64url JSON");
    }
    if (
      !Array.isArray(array) ||
      array.length !== 3 ||
      typeof array[0] !== "string" ||
      typeof array[1] !== "string" ||
      array[1] === "_sd" ||
      array[1] === "..."
    ) {
      throw new Error("A disclosure is not a [salt, name, value] array");
    }
    return new Disclosure(encoded, array[1], array[2]);
  }

  async digest(): Promise<string> {
    return toBase64Url(await sha256(ascii(this.encoded)));
  }
}

export class SdJwt {
  constructor(
    readonly jwt: Jws,
    readonly issuerJwt: string,
    readonly disclosures: Disclosure[],
  ) {}

  static parse(token: string): SdJwt {
    if (typeof token !== "string") {
      throw new Error("The token is missing");
    }
    const trimmed = token.trim();
    if (!trimmed.endsWith("~")) {
      throw new Error("The token is not an SD-JWT without key binding");
    }
    const parts = trimmed.split("~");
    const seen = new Set<string>();
    const disclosures: Disclosure[] = [];
    for (const part of parts.slice(1, -1)) {
      if (seen.has(part)) {
        throw new Error("A disclosure is repeated");
      }
      seen.add(part);
      disclosures.push(Disclosure.parse(part));
    }
    return new SdJwt(Jws.parse(parts[0]), parts[0], disclosures);
  }

  static async issue(header: Json, payload: Json, disclosures: Disclosure[], key: PrivateKey): Promise<string> {
    return (await Jws.sign(header, payload, key)) + "~" + disclosures.map((d) => d.encoded + "~").join("");
  }

  async claims(): Promise<Json> {
    const payload = this.jwt.payload;
    if ("_sd_alg" in payload && payload._sd_alg !== HASH_ALGORITHM) {
      throw new Error("The token hashes its disclosures with an unsupported algorithm");
    }
    const byDigest = new Map<string, Disclosure>();
    for (const disclosure of this.disclosures) {
      byDigest.set(await disclosure.digest(), disclosure);
    }
    const claims = JSON.parse(JSON.stringify(payload)) as Json;
    delete claims._sd_alg;
    const used = new Set<string>();
    reveal(claims, byDigest, used);
    if (used.size !== this.disclosures.length) {
      throw new Error("A disclosure is not referenced by the token");
    }
    return claims;
  }

  present(names: Iterable<string>): string {
    const wanted = new Set(names);
    return (
      this.issuerJwt + "~" + this.disclosures.filter((d) => wanted.has(d.name)).map((d) => d.encoded + "~").join("")
    );
  }
}

function reveal(node: unknown, byDigest: Map<string, Disclosure>, used: Set<string>): void {
  if (isObject(node)) {
    const digests = node._sd;
    delete node._sd;
    for (const name of Object.keys(node)) {
      reveal(node[name], byDigest, used);
    }
    if (digests === undefined) {
      return;
    }
    if (!Array.isArray(digests)) {
      throw new Error("The token has a malformed _sd claim");
    }
    for (const digest of digests) {
      const disclosure = typeof digest === "string" ? byDigest.get(digest) : undefined;
      if (!disclosure) {
        continue;
      }
      if (used.has(digest) || disclosure.name in node) {
        throw new Error(`The disclosed claim ${disclosure.name} is repeated`);
      }
      used.add(digest);
      node[disclosure.name] = disclosure.value;
    }
  } else if (Array.isArray(node)) {
    node.forEach((item) => reveal(item, byDigest, used));
  }
}

export interface MandateClaims {
  issuer: string;
  mandateId: string;
  agentId: string;
  principalId: string | null;
  audience: string | null;
  actions: string[];
  targets: string[];
  maxUses: number | null;
  grantId: string | null;
  termsHash: string | null;
  issuedAt: number;
  notBefore: number;
  expiresAt: number;
  statusListUrl: string;
  statusIndex: number;
}

export function mandateClaims(p: Json): MandateClaims {
  if (p.vct !== VCT) {
    throw new Error(`The token is not a ${VCT} credential`);
  }
  const m = isObject(p.mandate) ? p.mandate : {};
  const status = isObject(p.status) ? p.status : {};
  const s = isObject(status.status_list) ? status.status_list : {};
  return {
    issuer: text(p, "iss"),
    mandateId: uuid(text(p, "jti")),
    agentId: text(p, "sub"),
    principalId: optional(m, "principal"),
    audience: optional(p, "aud"),
    actions: strings(m.actions),
    targets: strings(m.targets),
    maxUses: typeof m.maxUses === "number" ? Math.trunc(m.maxUses) : null,
    grantId: typeof m.grant === "string" ? uuid(m.grant) : null,
    termsHash: optional(m, "termsHash"),
    issuedAt: number(p, "iat"),
    notBefore: number(p, "nbf"),
    expiresAt: number(p, "exp"),
    statusListUrl: text(s, "uri"),
    statusIndex: number(s, "idx"),
  };
}

export function covers(claims: MandateClaims, action: string, target: string | null): boolean {
  return claims.actions.some((p) => matches(p, action)) && claims.targets.some((p) => matches(p, target));
}

export function mandatePayload(c: MandateClaims): Json {
  const payload: Json = { iss: c.issuer, vct: VCT, sub: c.agentId };
  if (c.audience !== null) {
    payload.aud = c.audience;
  }
  Object.assign(payload, { jti: c.mandateId, iat: c.issuedAt, nbf: c.notBefore, exp: c.expiresAt });
  const mandate: Json = {};
  if (c.principalId !== null) {
    mandate.principal = c.principalId;
  }
  mandate.actions = [...c.actions];
  mandate.targets = [...c.targets];
  if (c.maxUses !== null) {
    mandate.maxUses = c.maxUses;
  }
  if (c.grantId !== null) {
    mandate.grant = c.grantId;
  }
  if (c.termsHash !== null) {
    mandate.termsHash = c.termsHash;
  }
  payload.mandate = mandate;
  payload.status = { status_list: { idx: c.statusIndex, uri: c.statusListUrl } };
  return payload;
}

function matches(pattern: string, value: string | null): boolean {
  if (value === null || value === undefined) {
    return pattern === "*";
  }
  return pattern.endsWith("*") ? value.startsWith(pattern.substring(0, pattern.length - 1)) : pattern === value;
}

function text(node: Json, name: string): string {
  if (typeof node[name] !== "string") {
    throw new Error("The mandate has no " + name);
  }
  return node[name] as string;
}

function optional(node: Json, name: string): string | null {
  return typeof node[name] === "string" ? (node[name] as string) : null;
}

function number(node: Json, name: string): number {
  if (typeof node[name] !== "number") {
    throw new Error("The mandate has no " + name);
  }
  return Math.trunc(node[name] as number);
}

function uuid(value: string): string {
  if (!UUID.test(value)) {
    throw new Error("Invalid UUID string: " + value);
  }
  return value.toLowerCase();
}

function strings(array: unknown): string[] {
  if (!Array.isArray(array) || array.length === 0) {
    throw new Error("The mandate has no actions or targets");
  }
  return array.map((item) => (typeof item === "string" ? item : JSON.stringify(item)));
}

async function transform(data: Uint8Array, stream: CompressionStream | DecompressionStream): Promise<Uint8Array> {
  const response = new Response(new Blob([data as BlobPart]).stream().pipeThrough(stream));
  return new Uint8Array(await response.arrayBuffer());
}

export class StatusList {
  constructor(
    readonly issuer: string | null,
    readonly uri: string | null,
    readonly issuedAt: number,
    readonly expiresAt: number,
    readonly size: number,
    readonly revoked: Uint8Array,
  ) {}

  static of(issuer: string, uri: string, issuedAt: number, expiresAt: number, size: number, revoked: number[]) {
    const bits = new Uint8Array(Math.ceil(size / 8));
    for (const index of revoked) {
      if (index >= 0 && index < size) {
        bits[index >> 3] |= 1 << (index % 8);
      }
    }
    return new StatusList(issuer, uri, issuedAt, expiresAt, size, bits);
  }

  static async fromPayload(payload: Json): Promise<StatusList> {
    const list = isObject(payload.status_list) ? payload.status_list : {};
    if (list.bits !== 1) {
      throw new Error("Only one bit per status is supported");
    }
    const size = typeof list.size === "number" ? Math.trunc(list.size) : 0;
    let data: Uint8Array;
    try {
      data = await transform(fromBase64Url(typeof list.lst === "string" ? list.lst : ""), new DecompressionStream("deflate"));
    } catch {
      throw new Error("The status list cannot be decompressed");
    }
    return new StatusList(optional(payload, "iss"), optional(payload, "sub"), Number(payload.iat ?? 0),
      Number(payload.exp ?? 0), size, data);
  }

  isRevoked(index: number): boolean {
    if (index < 0 || index >= this.size) {
      return true;
    }
    const byte = index >> 3;
    return byte < this.revoked.length && (this.revoked[byte] & (1 << (index % 8))) !== 0;
  }

  async sign(keyId: string, key: PrivateKey): Promise<string> {
    const lst = toBase64Url(await transform(this.revoked, new CompressionStream("deflate")));
    return Jws.signTyped(STATUS_LIST_TYPE, keyId, {
      iss: this.issuer,
      sub: this.uri,
      iat: this.issuedAt,
      exp: this.expiresAt,
      status_list: { bits: 1, size: this.size, lst },
    }, key);
  }
}

export function jwkPublicKey(jwk: Json): Promise<PublicKey> {
  if (jwk.kty !== "OKP" || jwk.crv !== "Ed25519") {
    throw new Error("Only Ed25519 OKP keys are supported");
  }
  const raw = fromBase64Url(typeof jwk.x === "string" ? jwk.x : "");
  if (raw.length !== 32) {
    throw new Error("An Ed25519 key has 32 bytes");
  }
  return PublicKey.fromRaw(raw);
}

export function jwkOf(key: PublicKey): Json {
  return { kty: "OKP", crv: "Ed25519", kid: key.keyId, use: "sig", alg: JWS_ALGORITHM, x: toBase64Url(key.raw) };
}

export type Fetcher = (url: string, accept: string) => Promise<string>;

export function httpFetcher(timeoutMillis = 10_000): Fetcher {
  return async (url, accept) => {
    const response = await fetch(url, { headers: { Accept: accept }, signal: AbortSignal.timeout(timeoutMillis) });
    if (response.status !== 200) {
      throw new Error(`${url} answered ${response.status}`);
    }
    return response.text();
  };
}

export interface KeyResolver {
  resolve(issuer: string, keyId: string): Promise<PublicKey | null>;
}

export interface StatusListResolver {
  resolve(issuer: string, uri: string): Promise<StatusList>;
}

export class StaticKeys implements KeyResolver {
  constructor(private readonly keys: Record<string, Record<string, PublicKey>>) {}

  static fixed(issuer: string, key: PublicKey): StaticKeys {
    return new StaticKeys({ [issuer.replace(/\/$/, "")]: { [key.keyId]: key } });
  }

  async resolve(issuer: string, keyId: string): Promise<PublicKey | null> {
    return this.keys[issuer]?.[keyId] ?? null;
  }
}

export type Clock = () => number;

const systemClock: Clock = () => Date.now() / 1000;

export class JwksKeys implements KeyResolver {
  static readonly MIN_REFRESH = 30;
  private readonly cache = new Map<string, { keys: Map<string, PublicKey>; fetchedAt: number }>();

  constructor(
    private readonly fetcher: Fetcher,
    private readonly ttl = 300,
    private readonly clock: Clock = systemClock,
  ) {}

  async resolve(issuer: string, keyId: string): Promise<PublicKey | null> {
    const now = this.clock();
    let cached = this.cache.get(issuer);
    const stale = !cached || now > cached.fetchedAt + this.ttl;
    const unknown = !!cached && !cached.keys.has(keyId) && now > cached.fetchedAt + JwksKeys.MIN_REFRESH;
    if (stale || unknown) {
      cached = { keys: await this.load(issuer), fetchedAt: now };
      this.cache.set(issuer, cached);
    }
    return cached!.keys.get(keyId) ?? null;
  }

  private async load(issuer: string): Promise<Map<string, PublicKey>> {
    const jwks = JSON.parse(await this.fetcher(issuer + KEYS_PATH, "application/jwk-set+json")) as Json;
    const keys = new Map<string, PublicKey>();
    for (const jwk of Array.isArray(jwks.keys) ? (jwks.keys as Json[]) : []) {
      if (!isObject(jwk) || typeof jwk.kid !== "string" || jwk.kty !== "OKP") {
        continue;
      }
      try {
        keys.set(jwk.kid, await jwkPublicKey(jwk));
      } catch {
        continue;
      }
    }
    return keys;
  }
}

export class HttpStatusLists implements StatusListResolver {
  private readonly cache = new Map<string, { list: StatusList; until: number }>();

  constructor(
    private readonly fetcher: Fetcher,
    private readonly keys: KeyResolver,
    private readonly maxAge = 300,
    private readonly clock: Clock = systemClock,
  ) {}

  async resolve(issuer: string, uri: string): Promise<StatusList> {
    const now = this.clock();
    const cached = this.cache.get(uri);
    if (cached && now < cached.until) {
      return cached.list;
    }
    const list = await this.verify(issuer, uri, await this.fetcher(uri, "application/statuslist+jwt"), now);
    this.cache.set(uri, { list, until: Math.min(list.expiresAt, now + this.maxAge) });
    return list;
  }

  private async verify(issuer: string, uri: string, token: string, now: number): Promise<StatusList> {
    const parsed = Jws.parse(token);
    if (parsed.header.typ !== STATUS_LIST_TYPE || parsed.header.alg !== JWS_ALGORITHM) {
      throw new Error(`The status list at ${uri} is not an EdDSA statuslist+jwt`);
    }
    const key = await this.keys.resolve(issuer, typeof parsed.header.kid === "string" ? parsed.header.kid : "");
    if (!key) {
      throw new Error(`The status list at ${uri} is signed with an unknown key`);
    }
    if (!(await parsed.verify(key))) {
      throw new Error(`The status list at ${uri} has a bad signature`);
    }
    const list = await StatusList.fromPayload(parsed.payload);
    if (list.issuer !== issuer || list.uri !== uri) {
      throw new Error(`The status list at ${uri} belongs to another issuer or address`);
    }
    if (!(now < list.expiresAt)) {
      throw new Error(`The status list at ${uri} has expired`);
    }
    return list;
  }
}

export interface Problem {
  code: MandateProblem;
  message: string;
}

export class MandateCheck {
  constructor(
    readonly claims: MandateClaims | null,
    readonly problems: Problem[] = [],
  ) {}

  get valid(): boolean {
    return this.claims !== null && this.problems.length === 0;
  }

  has(code: MandateProblem): boolean {
    return this.problems.some((problem) => problem.code === code);
  }
}

export interface MandateVerifierOptions {
  keys?: KeyResolver;
  statusLists?: StatusListResolver;
  skipStatus?: boolean;
  audience?: string | null;
  clock?: Clock;
  clockSkew?: number;
  cacheTtl?: number;
  timeoutMillis?: number;
  fetcher?: Fetcher;
}

export class MandateVerifier {
  private readonly issuers: Set<string>;
  private readonly keys: KeyResolver;
  private readonly statusLists: StatusListResolver | null;
  private readonly audience: string | null;
  private readonly clock: Clock;
  private readonly skew: number;

  constructor(issuers: string | string[], options: MandateVerifierOptions = {}) {
    const list = typeof issuers === "string" ? [issuers] : issuers;
    this.issuers = new Set(list.map((issuer) => issuer.replace(/\/$/, "")));
    if (this.issuers.size === 0) {
      throw new Error("At least one trusted issuer is required");
    }
    const fetcher = options.fetcher ?? httpFetcher(options.timeoutMillis);
    this.clock = options.clock ?? systemClock;
    this.skew = options.clockSkew ?? 60;
    this.audience = options.audience ?? null;
    const ttl = options.cacheTtl ?? 300;
    this.keys = options.keys ?? new JwksKeys(fetcher, ttl, this.clock);
    this.statusLists = options.skipStatus
      ? null
      : (options.statusLists ?? new HttpStatusLists(fetcher, this.keys, ttl, this.clock));
  }

  verify(token: string): Promise<MandateCheck> {
    return this.check(token, null, null, false);
  }

  verifyAction(token: string, action: string, target: string | null): Promise<MandateCheck> {
    return this.check(token, action, target, true);
  }

  private async check(token: string, action: string | null, target: string | null, coverage: boolean) {
    let parsed: Jws;
    let claims: MandateClaims;
    try {
      const sdJwt = SdJwt.parse(token);
      parsed = sdJwt.jwt;
      claims = mandateClaims(await sdJwt.claims());
    } catch (e) {
      return new MandateCheck(null, [{ code: "MALFORMED", message: (e as Error).message }]);
    }
    const fail = (code: MandateProblem, message: string) => new MandateCheck(claims, [{ code, message }]);
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
    if (now + this.skew < claims.notBefore) {
      problems.push({ code: "NOT_YET_VALID", message: "The mandate is valid from " + epochText(claims.notBefore) });
    }
    if (!(now - this.skew < claims.expiresAt)) {
      problems.push({ code: "EXPIRED", message: "The mandate expired at " + epochText(claims.expiresAt) });
    }
    if (this.audience !== null && this.audience !== claims.audience) {
      problems.push({ code: "WRONG_AUDIENCE", message: `The mandate is for ${claims.audience}, not ${this.audience}` });
    }
    if (coverage && !covers(claims, action as string, target)) {
      problems.push({ code: "NOT_COVERED", message: `The mandate does not cover ${action} on ${target}` });
    }
    if (this.statusLists) {
      problems.push(...(await this.status(claims)));
    }
    return new MandateCheck(claims, problems);
  }

  private async status(claims: MandateClaims): Promise<Problem[]> {
    if (!claims.statusListUrl.startsWith(claims.issuer + "/")) {
      return [{ code: "STATUS_UNAVAILABLE", message: `The status list ${claims.statusListUrl} is not served by the issuer` }];
    }
    try {
      const list = await this.statusLists!.resolve(claims.issuer, claims.statusListUrl);
      return list.isRevoked(claims.statusIndex) ? [{ code: "REVOKED", message: "The mandate has been revoked" }] : [];
    } catch (e) {
      return [{ code: "STATUS_UNAVAILABLE", message: `The revocation status cannot be checked: ${(e as Error).message}` }];
    }
  }
}
