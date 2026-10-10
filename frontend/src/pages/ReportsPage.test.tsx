import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { OPERATOR, mockLedger, renderApp } from '../test-support'

const REPORT = {
  format: 'nexusphere-ledger/report/v1', report: 'eu-ai-act-logging', title: 'EU AI Act record keeping',
  createdAt: '2026-10-10T10:00:00Z', profiles: [{ id: 'eu', digest: 'abc' }], subject: null,
  scope: { agentId: 'payments-agent', principalId: null, fromSequence: 1, toSequence: 5 },
  evidence: { checkpoint: 5, headHash: 'ff', entries: 3, entriesDigest: 'ee', valid: true },
  notice: 'This report maps recorded evidence to requirements.',
  sections: [{ id: 'art-12-3', reference: 'EU AI Act Article 12(3)', requirement: 'The input of each use can be traced',
    status: 'PARTLY_SHOWN', findings: ['2 of 3 events record the hash of their input'], entries: [4] }],
  keyId: 'k1', signature: 'sig',
}

describe('ReportsPage', () => {
  it('creates a report of an active profile and a dispute package', async () => {
    const calls = mockLedger({
      'GET /api/v1/reports': () => ({ body: [
        { id: 'dispute', title: 'Dispute package', description: '', profiles: ['eu'] },
        { id: 'eu-ai-act-logging', title: 'EU AI Act record keeping', description: '', profiles: ['eu'] },
      ] }),
      'POST /api/v1/reports/eu-ai-act-logging': (call) => ({ body: call.path.includes('format=html')
        ? '<html></html>' : REPORT }),
      'POST /api/v1/disputes': () => ({ body: 'zip' }),
    })
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined }))
    renderApp('/reports', OPERATOR)
    const user = userEvent.setup()

    await screen.findByRole('option', { name: 'EU AI Act record keeping (eu)' })
    await user.selectOptions(screen.getByLabelText('Report'), 'eu-ai-act-logging')
    await user.type(screen.getByLabelText('Agent'), 'payments-agent')
    await user.click(screen.getByRole('button', { name: 'Create report' }))

    expect(await screen.findByText('2 of 3 events record the hash of their input')).toBeInTheDocument()
    expect(screen.getByText('PARTLY_SHOWN')).toBeInTheDocument()
    expect(screen.getByText('Download report-eu-ai-act-logging-5.html')).toBeInTheDocument()
    expect(calls.find((c) => c.path === '/api/v1/reports/eu-ai-act-logging')!.body)
      .toEqual({ agentId: 'payments-agent' })

    await user.type(screen.getByLabelText('Incident'), 'Invoice 4711 paid twice')
    await user.click(screen.getByRole('button', { name: 'Create dispute package' }))

    expect(await screen.findByText('Download dispute.zip')).toBeInTheDocument()
    expect(calls.find((c) => c.path === '/api/v1/disputes')!.body)
      .toEqual({ agentId: 'payments-agent', title: 'Invoice 4711 paid twice' })
  })
})
