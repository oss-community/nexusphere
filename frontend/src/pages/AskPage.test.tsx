import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ALICE, OPERATOR, mockLedger, renderApp } from '../test-support'

function citation(sequence: number, overrides: Record<string, unknown> = {}) {
  return {
    sequence,
    id: `e-${sequence}`,
    occurredAt: '2026-10-11T07:15:00Z',
    agentId: 'invoice-agent',
    principalId: 'alice',
    action: 'tools/call',
    target: `target-${sequence}`,
    decision: 'DENY',
    outcome: 'DENIED',
    hash: `h-${sequence}`,
    erased: false,
    checks: { content: true, link: true, inclusion: true },
    ...overrides,
  }
}

function answer(overrides: Record<string, unknown> = {}) {
  return {
    question: 'Which calls were denied today?',
    answer: 'Two calls were denied today. Both came from invoice-agent.',
    sentences: [
      { text: 'Two calls were denied today.', citations: [3, 5] },
      { text: 'Both came from invoice-agent.', citations: [5] },
    ],
    droppedSentences: 2,
    citations: [
      citation(3),
      citation(5, { erased: true, principalId: null, target: null, checks: { content: true, link: false, inclusion: true } }),
    ],
    unverified: [4, 6],
    plan: { agentId: null, principalId: null, action: null, target: null, decision: 'DENY', outcome: null,
      from: '2026-10-11T00:00:00Z', to: null },
    model: 'llama3.1',
    considered: 12,
    checkpointSize: 40,
    recordedAs: 41,
    ...overrides,
  }
}

describe('AskPage', () => {
  it('answers the question of the operator sentence by sentence with citations', async () => {
    const calls = mockLedger({ 'POST /api/v1/ask': () => ({ body: answer() }) })
    const scrolled: string[] = []
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: function (this: HTMLElement) {
        scrolled.push(this.id)
      },
    })
    renderApp('/ask', OPERATOR)
    const user = userEvent.setup()

    expect(screen.getByRole('link', { name: 'Ask' })).toHaveClass('active')
    expect(screen.getByRole('button', { name: 'Ask' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Which calls were denied today?' }))
    expect(screen.getByLabelText('Question')).toHaveValue('Which calls were denied today?')
    expect(screen.getByText('30/500')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    const sentences = within(await screen.findByRole('list', { name: 'Answer' })).getAllByRole('listitem')
    expect(sentences.map((s) => s.textContent)).toEqual([
      'Two calls were denied today.#3#5',
      'Both came from invoice-agent.#5',
    ])
    expect(within(sentences[0]).getAllByRole('button').map((b) => b.textContent)).toEqual(['#3', '#5'])
    expect(within(screen.getByRole('list', { name: 'Search plan' })).getAllByRole('listitem').map((c) => c.textContent))
      .toEqual(['Decision DENY', 'From 2026-10-11T00:00:00Z'])
    expect(screen.getByText(/sentences without valid citations were removed/))
      .toHaveTextContent('Model llama3.1 · 12 entries considered · checkpoint size 40 · Recorded as #41 · 2 sentences without valid citations were removed')
    expect(screen.getByRole('status')).toHaveTextContent('Entries #4, #6 failed verification and were not used.')

    const table = (screen.getByRole('heading', { name: 'Cited evidence' })).closest('section')!
    const verified = within(table).getByText('3').closest('tr')!
    expect(within(verified).getByText('Verified')).toBeInTheDocument()
    const erased = within(table).getByText('5').closest('tr')!
    expect(within(erased).getAllByText('erased')).toHaveLength(2)
    expect(within(erased).getByText('link failed')).toBeInTheDocument()
    expect(within(erased).getByText('content passed')).toBeInTheDocument()
    expect(within(erased).getByText('inclusion passed')).toBeInTheDocument()

    await user.click(within(sentences[1]).getByRole('button', { name: '#5' }))
    expect(erased).toHaveClass('focused')
    expect(verified).not.toHaveClass('focused')
    expect(scrolled).toEqual(['cited-5'])

    expect(calls).toEqual([{ method: 'POST', path: '/api/v1/ask', authorization: 'Bearer operator-key',
      body: { question: 'Which calls were denied today?' } }])
  })

  it('asks about the evidence of the signed-in principal and says when nothing matches', async () => {
    const calls = mockLedger({
      'GET /api/v1/principal/activity': () => ({ body: { since: '2026-10-11T00:00:00Z', total: 0, allowed: 0, denied: 0,
        failed: 0, days: [], agents: [] } }),
      'GET /api/v1/principal/approvals': () => ({ body: { items: [] } }),
      'GET /api/v1/principal/grants': () => ({ body: { items: [], nextAfter: null } }),
      'GET /api/v1/principal/evidence/recent': () => ({ body: { items: [], nextBefore: null } }),
      'POST /api/v1/principal/ask': () => ({ body: answer({
        answer: 'No verified evidence matches the question.', sentences: [], citations: [], droppedSentences: 0,
        unverified: [], plan: null, considered: 0, checkpointSize: null,
      }) }),
    })
    renderApp('/my', ALICE)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('link', { name: 'Ask a question' }))
    expect(screen.getByRole('link', { name: 'Ask' })).toHaveClass('active')
    await user.type(screen.getByLabelText('Question'), 'What did my agents do this week?')
    await user.click(screen.getByRole('button', { name: 'Ask' }))

    expect(await screen.findByText('No verified evidence matches the question.')).toBeInTheDocument()
    expect(screen.getByText(/Recorded as/))
      .toHaveTextContent('Model llama3.1 · 0 entries considered · no checkpoint · Recorded as #41')
    expect(screen.queryByText(/without valid citations/)).not.toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Search plan' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Cited evidence' })).not.toBeInTheDocument()
    const ask = calls.find((c) => c.path === '/api/v1/principal/ask')!
    expect(ask.authorization).toBe('Bearer alice-token')
    expect(ask.body).toEqual({ question: 'What did my agents do this week?' })
  })

  it('shows the message of the ledger when the question is invalid or the model is down', async () => {
    const answers = [
      { status: 400, body: { code: 'INVALID_QUESTION', message: 'The question must have 1 to 500 characters.', details: [] } },
      { status: 502, body: { code: 'MODEL_UNAVAILABLE', message: 'The model is not available.' } },
    ]
    mockLedger({ 'POST /api/v1/ask': () => answers.shift()! })
    renderApp('/ask', OPERATOR)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Question'), 'denied?')
    await user.click(screen.getByRole('button', { name: 'Ask' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The question must have 1 to 500 characters.')

    await user.click(screen.getByRole('button', { name: 'Ask' }))
    expect(await screen.findByText('The model is not available.')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Answer' })).not.toBeInTheDocument()
  })

  it('shows the loading state while the ledger answers', async () => {
    let release: () => void = () => undefined
    mockLedger({})
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => {
      release = () => resolve(new Response(JSON.stringify(answer()), { status: 200 }))
    })))
    renderApp('/ask', OPERATOR)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Question'), 'denied?')
    await user.click(screen.getByRole('button', { name: 'Ask' }))
    expect(screen.getByRole('button', { name: 'Asking…' })).toBeDisabled()
    release()
    expect(await screen.findByRole('button', { name: 'Ask' })).toBeEnabled()
    expect(screen.getByText('Two calls were denied today.')).toBeInTheDocument()
  })
})
