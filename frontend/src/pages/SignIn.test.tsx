import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockLedger, renderApp } from '../test-support'

describe('SignIn', () => {
  it('opens the ledger with the operator key and keeps agent keys out', async () => {
    mockLedger({
      'GET /api/v1/agents': (call) => (call.authorization === 'Bearer operator-key'
        ? { body: { items: [], nextAfter: null } }
        : { status: 403, body: { code: 'FORBIDDEN', message: 'Only the ledger operator may do this.' } }),
      'GET /api/v1/ledger/head': () => ({ body: { sequence: 3, hash: 'f'.repeat(64) } }),
      'GET /api/v1/keys': () => ({ body: [] }),
    })
    renderApp('/sign-in')
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('Operator API key'), 'agent-key')
    await user.click(screen.getByRole('button', { name: 'Open the ledger' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('belongs to an agent')

    await user.clear(screen.getByLabelText('Operator API key'))
    await user.type(screen.getByLabelText('Operator API key'), 'operator-key')
    await user.click(screen.getByRole('button', { name: 'Open the ledger' }))

    expect(await screen.findByText('Head sequence')).toBeInTheDocument()
    expect(sessionStorage.getItem('nexusphere.operatorKey')).toBe('operator-key')
  })

  it('says when the ledger has no OpenID Connect provider', async () => {
    mockLedger({})
    renderApp('/sign-in')

    expect(await screen.findByText('This ledger has no OpenID Connect provider configured.')).toBeInTheDocument()
  })
})
