import type {
  Agent, Checkpoint, Erasure, Evidence, Exchange, Grant, GrantDraft, Head, IssuedAgent, LegalHold, Mandate, OidcInfo,
  PackageReport, Page, Principal, RetentionSweep, SigningKey, VerificationReport,
} from './types'

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

type Query = Record<string, string | number | undefined | null>

function withQuery(path: string, query?: Query): string {
  if (!query) {
    return path
  }
  const params = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  })
  const text = params.toString()
  return text ? `${path}?${text}` : path
}

export class LedgerApi {
  private readonly token: string | null

  constructor(token: string | null) {
    this.token = token
  }

  async request<T>(method: string, path: string, body?: unknown, query?: Query): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (this.token) {
      headers.Authorization = `Bearer ${this.token}`
    }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
    }
    const response = await fetch(withQuery(path, query), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await response.text()
    const json = text ? JSON.parse(text) : null
    if (!response.ok) {
      throw new ApiError(response.status, json?.code ?? 'HTTP_' + response.status,
        json?.message ?? `The ledger answered ${response.status}.`)
    }
    return json as T
  }

  head() {
    return this.request<Head>('GET', '/api/v1/ledger/head')
  }

  latestCheckpoint() {
    return this.request<Checkpoint>('GET', '/api/v1/checkpoints/latest')
  }

  createCheckpoint() {
    return this.request<Checkpoint>('POST', '/api/v1/checkpoints')
  }

  keys() {
    return this.request<SigningKey[]>('GET', '/api/v1/keys')
  }

  verify() {
    return this.request<VerificationReport>('GET', '/api/v1/verification')
  }

  evidence(query: { agentId?: string; principalId?: string; after?: number; limit?: number }) {
    return this.request<Page<Evidence>>('GET', '/api/v1/evidence', undefined, query)
  }

  agents(after?: string) {
    return this.request<Page<Agent>>('GET', '/api/v1/agents', undefined, { after, limit: 100 })
  }

  registerAgent(agent: { agentId: string; name: string; ownerId: string }) {
    return this.request<IssuedAgent>('POST', '/api/v1/agents', agent)
  }

  disableAgent(agentId: string) {
    return this.request<Agent>('POST', `/api/v1/agents/${encodeURIComponent(agentId)}/disable`)
  }

  grants(query: { agentId?: string; principalId?: string; after?: number }) {
    return this.request<Page<Grant>>('GET', '/api/v1/grants', undefined, { ...query, limit: 100 })
  }

  createGrant(draft: GrantDraft) {
    return this.request<Grant>('POST', '/api/v1/grants', draft)
  }

  revokeGrant(id: string, reason?: string) {
    return this.request<Grant>('POST', `/api/v1/grants/${id}/revoke`, { reason })
  }

  mandates(grantId: string) {
    return this.request<Mandate[]>('GET', '/api/v1/mandates', undefined, { grantId })
  }

  revokeMandate(id: string, reason?: string) {
    return this.request<Mandate>('POST', `/api/v1/mandates/${id}/revoke`, { reason })
  }

  exchange(id: string) {
    return this.request<Exchange>('GET', `/api/v1/a2a/exchanges/${id}`)
  }

  erasePrincipal(principalId: string, reason?: string) {
    return this.request<Erasure>('POST', `/api/v1/principals/${encodeURIComponent(principalId)}/erasure`, { reason })
  }

  legalHolds(active: boolean) {
    return this.request<{ items: LegalHold[] }>('GET', '/api/v1/legal-holds', undefined, { active: active ? 'true' : undefined })
  }

  placeLegalHold(principalId: string, reason: string) {
    return this.request<LegalHold>('POST', '/api/v1/legal-holds', { principalId, reason })
  }

  releaseLegalHold(id: string, reason: string) {
    return this.request<LegalHold>('POST', `/api/v1/legal-holds/${id}/release`, { reason })
  }

  sweepRetention() {
    return this.request<RetentionSweep>('POST', '/api/v1/retention/sweep')
  }

  exportPackage(scope: { agentId?: string; principalId?: string; fromSequence?: number; toSequence?: number }) {
    return this.request<unknown>('POST', '/api/v1/packages', scope)
  }

  verifyPackage(pkg: unknown, publicKey?: string) {
    return this.request<PackageReport>('POST', '/api/v1/packages/verify', pkg, { publicKey })
  }

  principal() {
    return this.request<Principal>('GET', '/api/v1/principal')
  }

  myGrants(state?: string, after?: number) {
    return this.request<Page<Grant>>('GET', '/api/v1/principal/grants', undefined, { state, after, limit: 100 })
  }

  createMyGrant(draft: GrantDraft) {
    return this.request<Grant>('POST', '/api/v1/principal/grants', draft)
  }

  approve(id: string) {
    return this.request<Grant>('POST', `/api/v1/principal/grants/${id}/approve`)
  }

  deny(id: string, reason?: string) {
    return this.request<Grant>('POST', `/api/v1/principal/grants/${id}/deny`, { reason })
  }

  revokeMine(id: string, reason?: string) {
    return this.request<Grant>('POST', `/api/v1/principal/grants/${id}/revoke`, { reason })
  }

  myEvidence(after?: number) {
    return this.request<Page<Evidence>>('GET', '/api/v1/principal/evidence', undefined, { after, limit: 100 })
  }

  oidc() {
    return this.request<OidcInfo>('GET', '/public/v1/oidc')
  }
}
