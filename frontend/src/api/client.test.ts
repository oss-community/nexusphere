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
})
