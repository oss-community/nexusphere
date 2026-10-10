import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { Evidence } from '../api/types'
import { EvidenceTable } from './EvidenceTable'

const ERASED: Evidence = {
  format: 'nexusphere-ledger/evidence/v2',
  id: '00000000-0000-4000-8000-000000000001',
  sequence: 1,
  occurredAt: '2026-10-01T08:00:00Z',
  recordedAt: '2026-10-01T08:00:00Z',
  agentId: 'invoice-agent',
  principalId: null,
  action: 'tools/call',
  target: null,
  decision: 'ALLOW',
  reason: null,
  delegationId: null,
  inputHash: null,
  outputHash: null,
  outcome: 'SUCCEEDED',
  correlationId: null,
  attributes: {},
  erased: true,
  salts: null,
  commitments: { principalId: 'ab'.repeat(32) },
  previousHash: '0'.repeat(64),
  hash: 'cd'.repeat(32),
}

describe('EvidenceTable', () => {
  it('marks an erased entry and shows its commitments', async () => {
    render(<EvidenceTable items={[ERASED]} />)
    expect(screen.getAllByText('erased')).toHaveLength(2)
    await userEvent.setup().click(screen.getByText('invoice-agent'))
    expect(screen.getByText('ab'.repeat(32))).toBeInTheDocument()
  })

  it('shows the model of an entry', async () => {
    const entry: Evidence = {
      ...ERASED,
      erased: false,
      principalId: 'alice',
      target: 'invoice-42',
      model: { name: 'gpt-5', version: '2026-09-01', promptHash: '12'.repeat(32), configHash: null },
    }
    render(<EvidenceTable items={[entry]} />)
    await userEvent.setup().click(screen.getByText('invoice-agent'))
    expect(screen.getByText('gpt-5 (2026-09-01)')).toBeInTheDocument()
    expect(screen.getByText('12'.repeat(32))).toBeInTheDocument()
    expect(screen.queryByText('Config hash')).not.toBeInTheDocument()
  })

  it('omits the model when the entry has none', async () => {
    render(<EvidenceTable items={[{ ...ERASED, model: null }]} />)
    await userEvent.setup().click(screen.getByText('invoice-agent'))
    expect(screen.queryByText('Model')).not.toBeInTheDocument()
  })
})
