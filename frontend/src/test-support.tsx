import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router'
import { vi } from 'vitest'
import { SessionProvider, type Session } from './auth/session'
import { AppRoutes } from './App'

export interface Call {
  method: string
  path: string
  authorization: string | null
  body: unknown
}

type Handler = (call: Call) => { status?: number; body?: unknown }

export function mockLedger(routes: Record<string, Handler>) {
  const calls: Call[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost')
    const method = init?.method ?? 'GET'
    const headers = (init?.headers ?? {}) as Record<string, string>
    const call: Call = {
      method,
      path: url.pathname + url.search,
      authorization: headers.Authorization ?? null,
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    }
    calls.push(call)
    const handler = routes[`${method} ${url.pathname}`]
    const answer = handler ? handler(call) : { status: 404, body: { code: 'NOT_FOUND', message: 'Not found' } }
    return new Response(answer.body === undefined ? '' : JSON.stringify(answer.body), {
      status: answer.status ?? 200,
      headers: { 'Content-Type': 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  return calls
}

export function renderApp(path: string, session?: Session): ReturnType<typeof render> {
  return render(
    <SessionProvider initial={session}>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
      </MemoryRouter>
    </SessionProvider>,
  )
}

export function renderWith(element: ReactElement, session: Session) {
  return render(
    <SessionProvider initial={session}>
      <MemoryRouter>{element}</MemoryRouter>
    </SessionProvider>,
  )
}

export const ALICE: Session = {
  kind: 'principal',
  token: 'alice-token',
  principal: {
    principalId: 'alice',
    name: 'Alice',
    issuer: 'http://localhost:8180/realms/nexusphere',
    subject: 'subject-alice',
    authTime: '2026-10-08T10:00:00Z',
    expiresAt: '2026-10-08T10:15:00Z',
  },
}

export const OPERATOR: Session = { kind: 'operator', token: 'operator-key' }

export function grant(id: string, status: string, consent: string | null = null) {
  return {
    id,
    principalId: 'alice',
    agentId: 'invoice-agent',
    actions: ['tools/call'],
    targets: ['demo/read_file'],
    notBefore: null,
    expiresAt: '2026-10-08T12:00:00Z',
    maxUses: 5,
    createdAt: '2026-10-08T10:00:00Z',
    termsHash: 'a'.repeat(64),
    uses: 0,
    status,
    reason: null,
    revokedAt: null,
    revokeReason: null,
    consent,
    consentedAt: null,
  }
}
