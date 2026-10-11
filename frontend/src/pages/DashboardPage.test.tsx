import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ALICE, approval, grant, mockLedger, renderApp, type Call } from '../test-support'

function activity(days: number) {
  const since = new Date(Date.UTC(2026, 9, 11) - (days - 1) * 24 * 60 * 60 * 1000).toISOString()
  return {
    since,
    total: 6,
    allowed: 4,
    denied: 1,
    failed: 1,
    days: [
      { day: '2026-10-09', total: 2, allowed: 2, denied: 0, failed: 0 },
      { day: '2026-10-11', total: 4, allowed: 2, denied: 1, failed: 1 },
    ],
    agents: [
      { agentId: 'invoice-agent', total: 5, allowed: 3, denied: 1, failed: 1, lastAt: '2026-10-11T09:30:00Z' },
      { agentId: 'travel-agent', total: 1, allowed: 1, denied: 0, failed: 0, lastAt: '2026-10-09T12:00:00Z' },
    ],
  }
}

function entry(sequence: number, recordedAt: string, agentId = 'invoice-agent') {
  return {
    format: 'nexusphere-ledger/evidence/v1',
    id: `e-${sequence}`,
    sequence,
    occurredAt: recordedAt,
    recordedAt,
    agentId,
    principalId: 'alice',
    action: 'tools/call',
    target: `target-${sequence}`,
    decision: sequence === 3 ? 'DENY' : 'ALLOW',
    reason: null,
    delegationId: null,
    inputHash: null,
    outputHash: null,
    outcome: sequence === 3 ? 'DENIED' : 'SUCCEEDED',
    correlationId: null,
    attributes: {},
    erased: false,
    salts: null,
    commitments: {},
    previousHash: 'p',
    hash: `h-${sequence}`,
  }
}

function ledger(overrides: Record<string, (call: Call) => { status?: number; body?: unknown }> = {}) {
  const inDays = (days: number) => new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString()
  return mockLedger({
    'GET /api/v1/principal/activity': (call) => ({
      body: activity(Number(new URL(call.path, 'http://localhost').searchParams.get('days'))),
    }),
    'GET /api/v1/principal/approvals': () => ({ body: { items: [] } }),
    'GET /api/v1/principal/grants': () => ({
      body: {
        items: [
          { ...grant('g-1', 'ACTIVE'), expiresAt: inDays(2), targets: ['soon/target'] },
          { ...grant('g-2', 'ACTIVE'), expiresAt: inDays(30), targets: ['later/target'] },
          { ...grant('g-3', 'REVOKED'), expiresAt: inDays(1), targets: ['revoked/target'] },
        ],
        nextAfter: null,
      },
    }),
    'GET /api/v1/principal/evidence/recent': () => ({ body: { items: [], nextBefore: null } }),
    ...overrides,
  })
}

describe('DashboardPage', () => {
  it('is the landing page of a principal and shows the activity of the chosen period', async () => {
    const calls = ledger()
    renderApp('/sign-in', ALICE)
    const user = userEvent.setup()

    const card = (await screen.findByRole('heading', { name: 'Activity' })).closest('section')!
    const bars = await within(card).findByRole('list', { name: 'Actions per day, last 30 days (UTC)' })
    expect(within(bars).getAllByRole('listitem')).toHaveLength(30)
    expect(within(card).getByText('Actions', { selector: 'dt' }).nextSibling).toHaveTextContent('6')
    expect(within(card).getByText('Denied', { selector: 'dt' }).nextSibling).toHaveTextContent('1')

    await user.click(within(card).getByRole('button', { name: '7 days' }))
    const week = await within(card).findByRole('list', { name: 'Actions per day, last 7 days (UTC)' })
    const items = within(week).getAllByRole('listitem')
    expect(items).toHaveLength(7)
    expect(items[0]).toHaveAttribute('title', '2026-10-05: 0 actions, 0 allowed, 0 denied, 0 failed')
    expect(items[6]).toHaveAttribute('title', '2026-10-11: 4 actions, 2 allowed, 1 denied, 1 failed')
    expect(items[6].querySelector('span')).toHaveStyle({ height: '100%' })
    expect(items[4].querySelector('span')).toHaveStyle({ height: '50%' })
    expect(items[5].querySelector('span')).toHaveStyle({ height: '0%' })
    const row = within(card).getByRole('cell', { name: 'invoice-agent' }).closest('tr')!
    expect(within(row).getAllByRole('cell').map((c) => c.textContent).slice(0, 5))
      .toEqual(['invoice-agent', '5', '3', '1', '1'])

    expect(calls.map((c) => c.path)).toContain('/api/v1/principal/activity?days=30')
    expect(calls.map((c) => c.path)).toContain('/api/v1/principal/activity?days=7')
    expect(calls.every((c) => c.authorization === 'Bearer alice-token')).toBe(true)
    expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveClass('active')
  })

  it('lets the principal answer the approvals waiting for them', async () => {
    const status: Record<string, string> = { 'a-1': 'PENDING', 'a-2': 'PENDING' }
    const calls = ledger({
      'GET /api/v1/principal/approvals': () => ({
        body: {
          items: [
            { ...approval('a-1', status['a-1']), approver: 'principal' },
            { ...approval('a-2', status['a-2']), approver: 'any' },
            { ...approval('a-3', 'PENDING'), approver: 'operator' },
          ].filter((a) => a.status === 'PENDING'),
        },
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
    const answers = ['fine', '']
    vi.stubGlobal('prompt', () => answers.shift())
    renderApp('/my', ALICE)
    const user = userEvent.setup()

    const waiting = (await screen.findByRole('heading', { name: 'Waiting for you (2)' })).closest('section')!
    expect(within(waiting).queryByText('a-3-target')).not.toBeInTheDocument()
    await user.click(within(within(waiting).getByText('a-1-target').closest('tr')!)
      .getByRole('button', { name: 'Approve' }))
    await screen.findByRole('heading', { name: 'Waiting for you (1)' })
    await user.click(within(screen.getByText('a-2-target').closest('tr')!).getByRole('button', { name: 'Deny' }))
    await screen.findByRole('heading', { name: 'Waiting for you (0)' })

    expect(calls.find((c) => c.path === '/api/v1/principal/approvals/a-1/approve')!.body).toEqual({ reason: 'fine' })
    expect(calls.find((c) => c.path === '/api/v1/principal/approvals/a-2/deny')!.body).toEqual({})
    expect(calls.map((c) => c.path)).toContain('/api/v1/principal/approvals?status=PENDING&limit=100')
    expect(within(waiting).getByRole('link', { name: 'My approvals' })).toHaveAttribute('href', '/my/approvals')
  })

  it('counts the active grants and lists the ones expiring within a week', async () => {
    ledger()
    renderApp('/my', ALICE)

    const card = (await screen.findByRole('heading', { name: 'Grants' })).closest('section')!
    expect(await within(card).findByText(/active grants, 1/)).toHaveTextContent('2 active grants, 1 expiring within 7 days.')
    expect(within(card).getByText(/soon\/target/)).toBeInTheDocument()
    expect(within(card).queryByText(/later\/target/)).not.toBeInTheDocument()
    expect(within(card).queryByText(/revoked\/target/)).not.toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'My grants' })).toHaveAttribute('href', '/my/grants')
  })

  it('shows the recent actions grouped by day, newest first, and loads more', async () => {
    const calls = ledger({
      'GET /api/v1/principal/evidence/recent': (call) => (call.path.includes('before=2')
        ? { body: { items: [entry(1, '2026-10-10T08:00:00Z', 'travel-agent')], nextBefore: null } }
        : {
            body: {
              items: [entry(4, '2026-10-11T07:45:00Z'), entry(3, '2026-10-11T07:15:00Z'), entry(2, '2026-10-10T09:00:00Z')],
              nextBefore: 2,
            },
          }),
    })
    renderApp('/my', ALICE)
    const user = userEvent.setup()

    const card = (await screen.findByRole('heading', { name: 'Recent actions' })).closest('section')!
    const today = await within(card).findByRole('region', { name: '2026-10-11' })
    const yesterday = within(card).getByRole('region', { name: '2026-10-10' })
    expect(within(today).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      '07:45invoice-agent tools/call target-4ALLOW SUCCEEDED',
      '07:15invoice-agent tools/call target-3DENY DENIED',
    ])
    expect(within(yesterday).getAllByRole('listitem')).toHaveLength(1)
    expect(calls.map((c) => c.path)).toContain('/api/v1/principal/evidence/recent?limit=50')

    await user.click(within(card).getByRole('button', { name: 'Load more' }))
    expect(await within(yesterday).findByText('travel-agent')).toBeInTheDocument()
    expect(within(yesterday).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      '09:00invoice-agent tools/call target-2ALLOW SUCCEEDED',
      '08:00travel-agent tools/call target-1ALLOW SUCCEEDED',
    ])
    expect(within(card).queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
    expect(calls.map((c) => c.path)).toContain('/api/v1/principal/evidence/recent?before=2&limit=50')
    expect(calls.some((c) => c.path.startsWith('/api/v1/principal/evidence?'))).toBe(false)
  })

  it('exports the evidence package of the principal as a JSON download', async () => {
    const pkg = { format: 'nexusphere-ledger/package/v1', scope: { agentId: 'invoice-agent', principalId: 'alice' },
      checkpoint: { sequence: 42 } }
    const calls = ledger({ 'POST /api/v1/principal/packages': () => ({ body: pkg }) })
    const blobs: Blob[] = []
    vi.stubGlobal('URL', Object.assign(URL, {
      createObjectURL: (blob: Blob) => {
        blobs.push(blob)
        return 'blob:package'
      },
      revokeObjectURL: () => undefined,
    }))
    const clicked: string[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(this.download)
    })
    renderApp('/my', ALICE)
    const user = userEvent.setup()

    const card = (await screen.findByRole('heading', { name: 'Export my evidence' })).closest('section')!
    await within(card).findByRole('option', { name: 'travel-agent' })
    await user.selectOptions(within(card).getByLabelText('Agent'), 'invoice-agent')
    await user.type(within(card).getByLabelText('From sequence'), '5')
    await user.click(within(card).getByRole('button', { name: 'Export' }))

    expect(await within(card).findByRole('link', { name: 'evidence-package-42.json' })).toHaveAttribute('href', 'blob:package')
    expect(clicked).toEqual(['evidence-package-42.json'])
    expect(JSON.parse(await blobs[0].text())).toEqual(pkg)
    expect(calls.find((c) => c.path === '/api/v1/principal/packages')!.body)
      .toEqual({ agentId: 'invoice-agent', fromSequence: 5 })
    expect(within(card).getByText(/verified offline in the Auditor Portal/)).toBeInTheDocument()
  })
})
