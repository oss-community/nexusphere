import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { OPERATOR, approval, mockLedger, renderApp } from '../test-support'

describe('ApprovalsPage', () => {
  it('lists the pending approvals and lets the operator approve and deny them', async () => {
    const stored: Record<string, ReturnType<typeof approval>> = {
      'a-1': approval('a-1', 'PENDING'),
      'a-2': approval('a-2', 'PENDING'),
    }
    const calls = mockLedger({
      'GET /api/v1/approvals': (call) => {
        const wanted = new URLSearchParams(call.path.split('?')[1]).get('status')
        return { body: { items: Object.values(stored).filter((a) => !wanted || a.status === wanted) } }
      },
      'POST /api/v1/approvals/a-1/approve': () => {
        stored['a-1'] = approval('a-1', 'APPROVED', 'operator')
        return { body: stored['a-1'] }
      },
      'POST /api/v1/approvals/a-2/deny': () => {
        stored['a-2'] = approval('a-2', 'DENIED', 'operator')
        return { body: stored['a-2'] }
      },
    })
    const answers = ['', 'not this month']
    vi.stubGlobal('prompt', () => answers.shift())
    renderApp('/approvals', OPERATOR)
    const user = userEvent.setup()

    const table = (await screen.findByText('a-1-target')).closest('table')!
    expect(within(table).getAllByText('pay_invoice')).toHaveLength(2)
    expect(within(table).getAllByText('policy1')).toHaveLength(2)
    await user.click(within(screen.getByText('a-1-target').closest('tr')!).getByRole('button', { name: 'Approve' }))
    expect(await screen.findByText('a-2-target')).toBeInTheDocument()
    await user.click(within(screen.getByText('a-2-target').closest('tr')!).getByRole('button', { name: 'Deny' }))
    expect(await screen.findByText('No approvals.')).toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox'), 'DENIED')
    expect(await screen.findByText('a-2-target')).toBeInTheDocument()
    expect(screen.getByText('operator', { selector: 'td[title]' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()

    expect(calls[0].path).toBe('/api/v1/approvals?status=PENDING&limit=100')
    expect(calls.find((c) => c.path === '/api/v1/approvals/a-1/approve')!.body).toEqual({})
    expect(calls.find((c) => c.path === '/api/v1/approvals/a-2/deny')!.body).toEqual({ reason: 'not this month' })
    expect(calls.some((c) => c.path === '/api/v1/approvals?status=DENIED&limit=100')).toBe(true)
    expect(screen.getByRole('link', { name: 'Approvals' })).toBeInTheDocument()
  })

  it('leaves the approvals the principal must answer to the principal', async () => {
    mockLedger({
      'GET /api/v1/approvals': () => ({ body: { items: [{ ...approval('a-1', 'PENDING'), approver: 'principal' }] } }),
    })
    renderApp('/approvals', OPERATOR)

    expect(await screen.findByText('a-1-target')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })
})
