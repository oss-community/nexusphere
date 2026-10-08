import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ALICE, grant, mockLedger, renderApp } from '../test-support'

describe('MyGrantsPage', () => {
  it('lets the principal approve a pending grant and revoke an active one', async () => {
    let pending = true
    const calls = mockLedger({
      'GET /api/v1/principal/grants': () => ({
        body: { items: [grant('g-1', pending ? 'PENDING' : 'ACTIVE', pending ? null : 'PRINCIPAL')], nextAfter: null },
      }),
      'POST /api/v1/principal/grants/g-1/approve': () => {
        pending = false
        return { body: grant('g-1', 'ACTIVE', 'PRINCIPAL') }
      },
      'POST /api/v1/principal/grants/g-1/revoke': () => ({ body: grant('g-1', 'REVOKED', 'PRINCIPAL') }),
    })
    renderApp('/my/grants', ALICE)
    const user = userEvent.setup()

    const waiting = (await screen.findByRole('heading', { name: 'Waiting for you (1)' })).closest('section')!
    await user.click(within(waiting).getByRole('button', { name: 'Approve' }))
    await screen.findByRole('heading', { name: 'Waiting for you (0)' })
    const mine = screen.getByRole('heading', { name: 'My grants' }).closest('section')!
    await user.click(within(mine).getByRole('button', { name: 'Revoke' }))

    expect(calls.map((c) => `${c.method} ${c.path}`)).toContain('POST /api/v1/principal/grants/g-1/approve')
    expect(calls.map((c) => `${c.method} ${c.path}`)).toContain('POST /api/v1/principal/grants/g-1/revoke')
    expect(calls.every((c) => c.authorization === 'Bearer alice-token')).toBe(true)
    expect(screen.getByText(/Signed in as/)).toHaveTextContent('Alice')
  })

  it('keeps a principal out of the operator pages', async () => {
    mockLedger({ 'GET /api/v1/principal/grants': () => ({ body: { items: [], nextAfter: null } }) })
    renderApp('/agents', ALICE)

    expect(await screen.findByRole('heading', { name: 'Waiting for you (0)' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Agents' })).not.toBeInTheDocument()
  })
})
