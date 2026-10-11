import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ALICE, approval, mockLedger, renderApp } from '../test-support'

describe('MyApprovalsPage', () => {
  it('lets the principal approve and deny the approvals waiting for them', async () => {
    const status: Record<string, string> = { 'a-1': 'PENDING', 'a-2': 'PENDING' }
    const calls = mockLedger({
      'GET /api/v1/principal/approvals': () => ({
        body: { items: Object.keys(status).map((id) => ({ ...approval(id, status[id]), approver: 'principal' })) },
      }),
      'POST /api/v1/principal/approvals/a-1/approve': () => {
        status['a-1'] = 'APPROVED'
        return { body: approval('a-1', 'APPROVED', 'principal') }
      },
      'POST /api/v1/principal/approvals/a-2/deny': () => {
        status['a-2'] = 'DENIED'
        return { body: approval('a-2', 'DENIED', 'principal') }
      },
    })
    const answers = ['looks right', '']
    vi.stubGlobal('prompt', () => answers.shift())
    renderApp('/my/approvals', ALICE)
    const user = userEvent.setup()

    const waiting = (await screen.findByRole('heading', { name: 'Approvals waiting for you (2)' })).closest('section')!
    await user.click(within(within(waiting).getByText('a-1-target').closest('tr')!)
      .getByRole('button', { name: 'Approve' }))
    await screen.findByRole('heading', { name: 'Approvals waiting for you (1)' })
    await user.click(within(screen.getByText('a-2-target').closest('tr')!).getByRole('button', { name: 'Deny' }))
    await screen.findByRole('heading', { name: 'Approvals waiting for you (0)' })
    const mine = screen.getByRole('heading', { name: 'My approvals' }).closest('section')!
    expect(within(mine).getByText('APPROVED')).toBeInTheDocument()
    expect(within(mine).getByText('DENIED')).toBeInTheDocument()

    expect(calls.find((c) => c.path === '/api/v1/principal/approvals/a-1/approve')!.body)
      .toEqual({ reason: 'looks right' })
    expect(calls.find((c) => c.path === '/api/v1/principal/approvals/a-2/deny')!.body).toEqual({})
    expect(calls.every((c) => c.authorization === 'Bearer alice-token')).toBe(true)
    expect(screen.getByRole('link', { name: 'My approvals' })).toBeInTheDocument()
  })

  it('does not answer when the principal cancels', async () => {
    const calls = mockLedger({
      'GET /api/v1/principal/approvals': () => ({ body: { items: [{ ...approval('a-1', 'PENDING'), approver: 'any' }] } }),
    })
    vi.stubGlobal('prompt', () => null)
    renderApp('/my/approvals', ALICE)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Approve' }))
    expect(calls.every((c) => c.method === 'GET')).toBe(true)
  })
})
