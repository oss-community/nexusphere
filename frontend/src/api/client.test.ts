import { describe, expect, it } from 'vitest'
import { mockLedger } from '../test-support'
import { ApiError, LedgerApi } from './client'

describe('LedgerApi', () => {
  it('sends the bearer token and leaves empty filters out of the query', async () => {
    const calls = mockLedger({ 'GET /api/v1/evidence': () => ({ body: { items: [], nextAfter: null } }) })

    await new LedgerApi('operator-key').evidence({ agentId: 'invoice-agent', principalId: '', limit: 100 })

    expect(calls[0].authorization).toBe('Bearer operator-key')
    expect(calls[0].path).toBe('/api/v1/evidence?agentId=invoice-agent&limit=100')
  })

  it('turns a ledger error into an ApiError with its code and message', async () => {
    mockLedger({
      'POST /api/v1/principal/grants/g-1/approve': () => ({
        status: 409, body: { code: 'GRANT_NOT_PENDING', message: 'Grant g-1 is ACTIVE.' },
      }),
    })

    const failure = await new LedgerApi('alice-token').approve('g-1').catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiError)
    expect((failure as ApiError).status).toBe(409)
    expect((failure as ApiError).code).toBe('GRANT_NOT_PENDING')
    expect((failure as ApiError).message).toBe('Grant g-1 is ACTIVE.')
  })

  it('reads the activity of the signed-in principal for the asked period', async () => {
    const calls = mockLedger({
      'GET /api/v1/principal/activity': () => ({
        body: { since: '2026-10-05T00:00:00Z', total: 0, allowed: 0, denied: 0, failed: 0, days: [], agents: [] },
      }),
    })

    const activity = await new LedgerApi('alice-token').myActivity(7)

    expect(calls[0].path).toBe('/api/v1/principal/activity?days=7')
    expect(calls[0].authorization).toBe('Bearer alice-token')
    expect(activity.since).toBe('2026-10-05T00:00:00Z')
  })

  it('exports the evidence package of the signed-in principal with an optional scope', async () => {
    const calls = mockLedger({
      'POST /api/v1/principal/packages': () => ({ body: { scope: { principalId: 'alice' }, checkpoint: { sequence: 9 } } }),
    })
    const api = new LedgerApi('alice-token')

    const pkg = await api.exportMyPackage({ agentId: 'invoice-agent', toSequence: 9 })
    await api.exportMyPackage()

    expect(pkg.scope?.principalId).toBe('alice')
    expect(calls.map((c) => [c.method, c.path, c.body])).toEqual([
      ['POST', '/api/v1/principal/packages', { agentId: 'invoice-agent', toSequence: 9 }],
      ['POST', '/api/v1/principal/packages', {}],
    ])
  })

  it('pages the evidence about the principal from the newest entry backwards', async () => {
    const calls = mockLedger({
      'GET /api/v1/principal/evidence/recent': () => ({ body: { items: [], nextBefore: 7 } }),
    })
    const api = new LedgerApi('alice-token')

    const first = await api.myRecentEvidence()
    await api.myRecentEvidence(7, 20)

    expect(first.nextBefore).toBe(7)
    expect(calls.map((c) => c.path)).toEqual([
      '/api/v1/principal/evidence/recent?limit=50',
      '/api/v1/principal/evidence/recent?before=7&limit=20',
    ])
  })
})
