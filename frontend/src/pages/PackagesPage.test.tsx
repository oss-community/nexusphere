import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { OPERATOR, mockLedger, renderApp } from '../test-support'

describe('PackagesPage', () => {
  it('verifies an uploaded package against the pinned active key', async () => {
    const calls = mockLedger({
      'GET /api/v1/keys': () => ({ body: [
        { keyId: 'old', publicKey: 'OLD', status: 'RETIRED' },
        { keyId: 'new', publicKey: 'NEW', status: 'ACTIVE' },
      ] }),
      'POST /api/v1/packages/verify': () => ({ body: {
        valid: false, keyId: 'new', pinnedKeyId: 'new', anchorSequence: null, checkpointSequence: 9,
        checkpointCreatedAt: '2026-10-08T10:00:00Z', complianceProfiles: [], firstSequence: 1, checkedLinks: 8, disclosedEntries: 8,
        agentId: 'invoice-agent', principalId: null, problems: ['sequence 6: the content hash does not match'],
      } }),
    })
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined }))
    renderApp('/packages', OPERATOR)
    const user = userEvent.setup()

    await screen.findByDisplayValue('NEW')
    const file = new File([JSON.stringify({ format: 'nexusphere-ledger/package/v1' })], 'package.json',
      { type: 'application/json' })
    await user.upload(screen.getByLabelText('Package file'), file)
    await user.click(screen.getByRole('button', { name: 'Verify' }))

    expect(await screen.findByText('Result: INVALID')).toBeInTheDocument()
    expect(screen.getByText('sequence 6: the content hash does not match')).toBeInTheDocument()
    const verify = calls.find((c) => c.path.startsWith('/api/v1/packages/verify'))!
    expect(verify.path).toBe('/api/v1/packages/verify?publicKey=NEW')
    expect(verify.body).toEqual({ format: 'nexusphere-ledger/package/v1' })
  })
})
