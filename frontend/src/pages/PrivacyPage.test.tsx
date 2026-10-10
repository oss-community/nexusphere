import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { OPERATOR, mockLedger, renderApp } from '../test-support'

const HOLD = {
  id: 'h-1', principalRef: 'r-1', principalId: 'alice', reason: 'dispute 42', placedAt: '2026-10-10T10:00:00Z',
  releasedAt: null, releaseReason: null,
}

describe('PrivacyPage', () => {
  it('erases a principal, places and releases a legal hold and runs retention', async () => {
    let held = false
    const calls = mockLedger({
      'GET /api/v1/legal-holds': () => ({ body: { items: held ? [HOLD] : [] } }),
      'POST /api/v1/legal-holds': () => {
        held = true
        return { status: 201, body: HOLD }
      },
      'POST /api/v1/legal-holds/h-1/release': () => {
        held = false
        return { body: { ...HOLD, releasedAt: '2026-10-10T11:00:00Z', releaseReason: 'settled' } }
      },
      'POST /api/v1/principals/bob/erasure': () => ({ body: {
        principalRef: 'r-2', erasedEntries: 2, retainedEntries: 1, retainedUntil: '2027-01-01T00:00:00Z',
        legalHold: false, completed: false, evidenceId: 'e-1',
      } }),
      'POST /api/v1/retention/sweep': () => ({ body: { expiredEntries: 3, erasures: [] } }),
    })
    vi.stubGlobal('prompt', () => 'settled')
    renderApp('/privacy', OPERATOR)
    const user = userEvent.setup()

    expect(await screen.findByText('No active legal holds.')).toBeInTheDocument()
    const [erasePrincipal, , holdPrincipal, holdReason] = screen.getAllByRole('textbox')
    await user.type(erasePrincipal, 'bob')
    await user.click(screen.getByRole('button', { name: 'Erase' }))
    expect(await screen.findByText(/2 entries erased, 1 retained/)).toBeInTheDocument()

    await user.type(holdPrincipal, 'alice')
    await user.type(holdReason, 'dispute 42')
    await user.click(screen.getByRole('button', { name: 'Place hold' }))
    expect(await screen.findByText('dispute 42')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Release' }))
    expect(await screen.findByText('No active legal holds.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Run now' }))
    expect(await screen.findByText('3 entries expired, 0 erasures continued.')).toBeInTheDocument()
    expect(calls.find((c) => c.path === '/api/v1/legal-holds/h-1/release')!.body).toEqual({ reason: 'settled' })
    expect(calls.some((c) => c.path === '/api/v1/legal-holds?active=true')).toBe(true)
  })
})
