import { sha256Hex } from "./canonical.js";
import { formatInstant } from "./timestamps.js";

type Json = Record<string, unknown>;

export class LedgerError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    readonly detail: string,
    readonly details: Json = {},
  ) {
    super(`${status} ${code ?? "ERROR"}: ${detail}`);
  }
}

export class Denied extends Error {
  constructor(readonly decision: Json) {
    super(`${decision.reasonCode}: ${decision.reason}`);
  }
}

export function hashOf(data: unknown): Promise<string> {
  if (data instanceof Uint8Array || typeof data === "string") {
    return sha256Hex(data);
  }
  return sha256Hex(JSON.stringify(sortKeys(data)));
}

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sortKeys);
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value as Json)
        .sort()
        .map((key) => [key, sortKeys((value as Json)[key])]),
    );
  }
  return value;
}

export interface EvidenceInput {
  agentId?: string;
  principalId?: string;
  action?: string;
  outcome?: "SUCCEEDED" | "FAILED" | "DENIED" | "PENDING";
  occurredAt?: string | Date;
  target?: string;
  decision?: "ALLOW" | "DENY";
  reason?: string;
  delegationId?: string;
  inputHash?: string;
  outputHash?: string;
  correlationId?: string;
  attributes?: Record<string, string>;
  input?: unknown;
  output?: unknown;
}

export interface DecideInput {
  principalId: string;
  action: string;
  target?: string;
  inputHash?: string;
  input?: unknown;
  correlationId?: string;
  attributes?: Record<string, string>;
  agentId?: string;
}

export class Action {
  outputHash: string | null = null;
  attributes: Record<string, string> | null = null;
  reported: Json | null = null;

  constructor(readonly decision: Json) {}

  get decisionId(): string {
    return this.decision.decisionId as string;
  }

  async output(data: unknown): Promise<void> {
    this.outputHash = await hashOf(data);
  }
}

export interface ClientOptions {
  apiKey?: string;
  timeoutMillis?: number;
  fetch?: typeof fetch;
}

export class LedgerClient {
  readonly baseUrl: string;
  private readonly apiKey: string | undefined;
  private readonly timeoutMillis: number;
  private readonly fetcher: typeof fetch;

  constructor(baseUrl: string, options: ClientOptions = {}) {
    this.baseUrl = baseUrl.replace(/\/+$/, "");
    this.apiKey = options.apiKey;
    this.timeoutMillis = options.timeoutMillis ?? 10_000;
    this.fetcher = options.fetch ?? fetch;
  }

  async record(input: EvidenceInput): Promise<Json> {
    return this.post("/api/v1/evidence", await evidence(input));
  }

  async recordBatch(items: EvidenceInput[]): Promise<Json[]> {
    const body = { items: await Promise.all(items.map(evidence)) };
    return ((await this.post("/api/v1/evidence/batch", body)) as { items: Json[] }).items;
  }

  evidence(id: string): Promise<Json> {
    return this.get("/api/v1/evidence/" + encodeURIComponent(id));
  }

  listEvidence(query: { agentId?: string; principalId?: string; after?: number; limit?: number } = {}): Promise<Json> {
    return this.get("/api/v1/evidence", query);
  }

  async *iterEvidence(query: { agentId?: string; principalId?: string; after?: number; limit?: number } = {}) {
    let after = query.after ?? 0;
    while (true) {
      const page = (await this.listEvidence({ ...query, after, limit: query.limit ?? 500 })) as {
        items: Json[];
        nextAfter: number | null;
      };
      yield* page.items;
      if (page.nextAfter === null || page.nextAfter === undefined) {
        return;
      }
      after = page.nextAfter;
    }
  }

  statement(id: string): Promise<Uint8Array> {
    return this.bytes(`/api/v1/evidence/${encodeURIComponent(id)}/statement`);
  }

  receipt(id: string, treeSize?: number): Promise<Uint8Array> {
    return this.bytes(`/api/v1/evidence/${encodeURIComponent(id)}/receipt`, { treeSize });
  }

  head(): Promise<Json> {
    return this.get("/api/v1/ledger/head");
  }

  createCheckpoint(): Promise<Json> {
    return this.post("/api/v1/checkpoints", {});
  }

  latestCheckpoint(): Promise<Json> {
    return this.get("/api/v1/checkpoints/latest");
  }

  checkpoints(after = 0, limit = 100): Promise<Json> {
    return this.get("/api/v1/checkpoints", { after, limit });
  }

  inclusionProof(sequence: number, treeSize?: number): Promise<Json> {
    return this.get("/api/v1/log/proofs/inclusion", { sequence, treeSize });
  }

  consistencyProof(firstSize: number, secondSize?: number): Promise<Json> {
    return this.get("/api/v1/log/proofs/consistency", { firstSize, secondSize });
  }

  keys(): Promise<Json[]> {
    return this.get("/api/v1/keys") as unknown as Promise<Json[]>;
  }

  async activePublicKey(): Promise<string> {
    const key = (await this.keys()).find((k) => k.status === "ACTIVE");
    if (!key) {
      throw new Error("The ledger has no active key");
    }
    return key.publicKey as string;
  }

  exportPackage(scope: { agentId?: string; principalId?: string; fromSequence?: number; toSequence?: number } = {}) {
    return this.post("/api/v1/packages", compact(scope));
  }

  async decide(input: DecideInput): Promise<Json> {
    const inputHash = input.inputHash ?? (input.input === undefined ? undefined : await hashOf(input.input));
    return this.post("/api/v1/decisions", compact({
      agentId: input.agentId,
      principalId: input.principalId,
      action: input.action,
      target: input.target,
      inputHash,
      correlationId: input.correlationId,
      attributes: input.attributes,
    }));
  }

  reportOutcome(
    decisionId: string,
    outcome: "SUCCEEDED" | "FAILED",
    extra: { outputHash?: string | null; reason?: string | null; attributes?: Record<string, string> | null } = {},
  ): Promise<Json> {
    return this.post(`/api/v1/decisions/${encodeURIComponent(decisionId)}/outcome`, compact({ outcome, ...extra }));
  }

  decision(id: string): Promise<Json> {
    return this.get("/api/v1/decisions/" + encodeURIComponent(id));
  }

  async act<T>(input: DecideInput, work: (action: Action) => Promise<T> | T): Promise<T> {
    const decision = await this.decide(input);
    if (decision.decision !== "ALLOW") {
      throw new Denied(decision);
    }
    const action = new Action(decision);
    let result: T;
    try {
      result = await work(action);
    } catch (e) {
      action.reported = await this.reportOutcome(action.decisionId, "FAILED", {
        outputHash: action.outputHash,
        reason: e instanceof Error ? e.name : "Error",
        attributes: action.attributes,
      });
      throw e;
    }
    action.reported = await this.reportOutcome(action.decisionId, "SUCCEEDED", {
      outputHash: action.outputHash,
      attributes: action.attributes,
    });
    return result;
  }

  registerAgent(agentId: string, name?: string, ownerId?: string): Promise<Json> {
    return this.post("/api/v1/agents", compact({ agentId, name, ownerId }));
  }

  setSigningKey(agentId: string, publicKey: string | { encoded: string }): Promise<Json> {
    const encoded = typeof publicKey === "string" ? publicKey : publicKey.encoded;
    return this.request("PUT", `/api/v1/agents/${encodeURIComponent(agentId)}/signing-key`, undefined,
      { publicKey: encoded }) as Promise<Json>;
  }

  createGrant(grant: {
    principalId: string;
    agentId: string;
    actions: string[];
    targets: string[];
    expiresAt: string | Date;
    notBefore?: string | Date;
    maxUses?: number;
  }): Promise<Json> {
    return this.post("/api/v1/grants", compact({
      ...grant,
      expiresAt: formatInstant(grant.expiresAt),
      notBefore: grant.notBefore === undefined ? undefined : formatInstant(grant.notBefore),
    }));
  }

  grant(id: string): Promise<Json> {
    return this.get("/api/v1/grants/" + encodeURIComponent(id));
  }

  revokeGrant(id: string, reason?: string): Promise<Json> {
    return this.post(`/api/v1/grants/${encodeURIComponent(id)}/revoke`, compact({ reason }));
  }

  issueMandate(grantId: string, audience?: string, expiresAt?: string | Date): Promise<Json> {
    return this.post("/api/v1/mandates", compact({
      grantId,
      audience,
      expiresAt: expiresAt === undefined ? undefined : formatInstant(expiresAt),
    }));
  }

  revokeMandate(id: string, reason?: string): Promise<Json> {
    return this.post(`/api/v1/mandates/${encodeURIComponent(id)}/revoke`, compact({ reason }));
  }

  jwks(): Promise<Json> {
    return this.get("/public/v1/keys");
  }

  async logCheckpoint(): Promise<string> {
    return new TextDecoder().decode(await this.bytes("/public/v1/log/checkpoint"));
  }

  async logKey(): Promise<string> {
    return new TextDecoder().decode(await this.bytes("/public/v1/log/key")).trim();
  }

  async witnessKey(): Promise<string> {
    return new TextDecoder().decode(await this.bytes("/public/v1/witness/key")).trim();
  }

  erasePrincipal(principalId: string, reason?: string): Promise<Json> {
    return this.post(`/api/v1/principals/${encodeURIComponent(principalId)}/erasure`, compact({ reason }));
  }

  placeLegalHold(principalId: string, reason: string): Promise<Json> {
    return this.post("/api/v1/legal-holds", { principalId, reason });
  }

  releaseLegalHold(holdId: string, reason: string): Promise<Json> {
    return this.post(`/api/v1/legal-holds/${encodeURIComponent(holdId)}/release`, { reason });
  }

  legalHolds(active = false): Promise<Json> {
    return this.get("/api/v1/legal-holds", active ? { active: "true" } : {});
  }

  sweepRetention(): Promise<Json> {
    return this.post("/api/v1/retention/sweep", {});
  }

  compliance(): Promise<Json> {
    return this.get("/public/v1/compliance");
  }

  health(): Promise<Json> {
    return this.get("/actuator/health");
  }

  private get(path: string, query?: Record<string, unknown>): Promise<Json> {
    return this.request("GET", path, query, undefined) as Promise<Json>;
  }

  private post(path: string, body: unknown): Promise<Json> {
    return this.request("POST", path, undefined, body ?? {}) as Promise<Json>;
  }

  private bytes(path: string, query?: Record<string, unknown>): Promise<Uint8Array> {
    return this.request("GET", path, query, undefined, true) as Promise<Uint8Array>;
  }

  private async request(method: string, path: string, query?: Record<string, unknown>, body?: unknown, raw = false) {
    let url = this.baseUrl + path;
    const params = Object.entries(query ?? {}).filter(([, value]) => value !== undefined && value !== null);
    if (params.length) {
      url += "?" + new URLSearchParams(params.map(([key, value]) => [key, String(value)])).toString();
    }
    const headers: Record<string, string> = { Accept: raw ? "*/*" : "application/json" };
    if (this.apiKey) {
      headers.Authorization = "Bearer " + this.apiKey;
    }
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    const response = await this.fetcher(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(this.timeoutMillis),
    });
    if (!response.ok) {
      let error: Json = {};
      try {
        error = (await response.json()) as Json;
      } catch {
        error = {};
      }
      throw new LedgerError(response.status, (error.code as string) ?? null,
        (error.message as string) ?? response.statusText, (error.details as Json) ?? {});
    }
    if (raw) {
      return new Uint8Array(await response.arrayBuffer());
    }
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  }
}

async function evidence(input: EvidenceInput): Promise<Json> {
  const { input: given, output, occurredAt, ...rest } = input;
  return compact({
    ...rest,
    occurredAt: occurredAt === undefined ? undefined : formatInstant(occurredAt),
    inputHash: rest.inputHash ?? (given === undefined ? undefined : await hashOf(given)),
    outputHash: rest.outputHash ?? (output === undefined ? undefined : await hashOf(output)),
  });
}

function compact(values: Json): Json {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== undefined && value !== null));
}
