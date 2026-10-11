import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import { GENESIS, contentHash, linkHash } from '@nexusphere/ledger'
import type { Json } from '@nexusphere/ledger'
import {
  LEDGER_KEY,
  buildPackage,
  buildReport,
  entries,
  keyRecord,
  salts,
  signReport,
  witnessNoteKey,
  zip,
} from '../../sdk/typescript/test/support'

const PINNED = LEDGER_KEY.publicKey.encoded

async function verified(pkg: unknown, options: { witness?: boolean } = {}) {
  const user = userEvent.setup()
  render(<App />)
  await user.upload(screen.getByLabelText('Package file'), new File([JSON.stringify(pkg)], 'package.json'))
  await user.type(screen.getByLabelText('Ledger public key'), PINNED)
  if (options.witness) {
    await user.type(screen.getByLabelText('Witness keys, one per line'), (await witnessNoteKey()).vkey)
  }
  await user.click(screen.getByRole('button', { name: 'Verify' }))
  await screen.findByTestId('verdict')
  return user
}

function checks(): Record<string, string> {
  const table = within(screen.getByRole('region', { name: /^Entry \d+$/ })).getByRole('table')
  return Object.fromEntries(
    within(table).getAllByRole('row').slice(1).map((row) => {
      const cells = within(row).getAllByRole('cell')
      return [cells[0].textContent!, cells[1].textContent!]
    }),
  )
}

async function longChain(count: number): Promise<Json[]> {
  const chain: Json[] = []
  let previous = GENESIS
  for (let sequence = 1; sequence <= count; sequence++) {
    const minute = String(Math.floor(sequence / 60)).padStart(2, '0')
    const second = String(sequence % 60).padStart(2, '0')
    const entry: Json = {
      id: '00000000-0000-4000-8000-' + String(sequence).padStart(12, '0'),
      sequence,
      occurredAt: `2026-10-01T08:${minute}:${second}.123456Z`,
      recordedAt: `2026-10-01T08:${minute}:${second}.128456Z`,
      agentId: 'invoice-agent',
      principalId: 'acme',
      action: 'tools/call',
      target: 'read_invoice',
      decision: 'ALLOW',
      reason: null,
      delegationId: null,
      inputHash: null,
      outputHash: null,
      outcome: 'SUCCEEDED',
      correlationId: null,
      attributes: {},
      previousHash: previous,
      salts: await salts(sequence, ['principalId', 'target', 'reason', 'correlationId']),
    }
    entry.hash = await linkHash(sequence, previous, await contentHash(entry))
    previous = entry.hash as string
    chain.push(entry)
  }
  return chain
}

async function reportWithEntries(pkg: Json, sequences: number[]): Promise<Json> {
  const report = await buildReport(pkg)
  const sections = report.sections as Json[]
  return signReport({
    ...report,
    sections: [
      { ...sections[0], entries: sequences },
      {
        id: 'integrity',
        reference: 'Integrity',
        requirement: 'The evidence has not been changed since it was recorded',
        status: 'PARTLY_SHOWN',
        findings: ['Not timestamped by a timestamp authority'],
        entries: [],
      },
    ],
  })
}

describe('Auditor Portal', async () => {
  const chain = await entries(6)

  it('shows the tabs of the workspace with the overview first', async () => {
    const user = await verified(await buildPackage(chain, [2, 3]))
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Auditor Portal')
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual(['Overview', 'Entries', 'Chain', 'Log', 'Report', 'Export'])
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', 'tab-overview')
    expect(within(screen.getByRole('tabpanel')).getByText('Checked links')).toBeInTheDocument()
    screen.getByRole('tab', { name: 'Overview' }).focus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Entries' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Entries' })).toHaveFocus()
    await user.keyboard('{End}')
    expect(screen.getByRole('tab', { name: 'Export' })).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
  })

  it('filters the entries and opens one with every check passed', async () => {
    const user = await verified(await buildPackage(chain, [1, 2, 3, 4, 5]))
    await user.click(screen.getByRole('tab', { name: 'Entries' }))
    expect(await screen.findByTestId('entry-count')).toHaveTextContent('5 of 5 entries')
    expect(within(screen.getByLabelText('Agent')).getAllByRole('option').map((option) => option.textContent))
      .toEqual(['Any', 'invoice-agent', 'sales-agent'])
    await user.selectOptions(screen.getByLabelText('Agent'), 'sales-agent')
    expect(screen.getByTestId('entry-count')).toHaveTextContent('2 of 5 entries')
    await user.selectOptions(screen.getByLabelText('Principal'), 'globex')
    expect(screen.getByTestId('entry-count')).toHaveTextContent('2 of 5 entries')
    await user.type(screen.getByLabelText('Search target and attributes'), 'nothing like it')
    expect(screen.getByTestId('entry-count')).toHaveTextContent('0 of 5 entries')
    expect(screen.getByText('No entry matches.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-01' } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-10-01' } })
    expect(screen.getByTestId('entry-count')).toHaveTextContent('5 of 5 entries')
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-02' } })
    expect(screen.getByTestId('entry-count')).toHaveTextContent('0 of 5 entries')
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    await user.click(screen.getByRole('button', { name: 'Open entry 4' }))
    const detail = await screen.findByRole('region', { name: 'Entry 4' })
    expect(await within(detail).findByTestId('entry-verdict')).toHaveTextContent('All present checks passed')
    expect(checks()).toEqual({
      'Content hash': 'passed',
      'Entry hash': 'passed',
      'Hash link': 'passed',
      'Inclusion proof': 'passed',
      'SCITT statement': 'passed',
      'SCITT receipt': 'passed',
    })
    for (const field of ['id', 'recordedAt', 'attributes.tool', 'delegationId', 'inputHash', 'outputHash',
      'commitments.principalId', 'previousHash', 'hash']) {
      expect(within(detail).getByText(field, { selector: 'dt' })).toBeInTheDocument()
    }
    expect(within(detail).getByText(chain[3].hash as string, { selector: 'dd' })).toBeInTheDocument()
    await user.click(within(detail).getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('region', { name: 'Entry 4' })).not.toBeInTheDocument()
  })

  it('shows the failed checks of a tampered entry', async () => {
    const pkg = (await buildPackage(chain, [2, 3])) as any
    pkg.links[2].entry.target = 'delete_invoice'
    const user = await verified(pkg)
    expect(screen.getByTestId('verdict')).toHaveTextContent('INVALID')
    await user.click(screen.getByRole('tab', { name: 'Entries' }))
    await user.click(await screen.findByRole('button', { name: 'Open entry 3' }))
    expect(await screen.findByTestId('entry-verdict')).toHaveTextContent('A check failed')
    expect(checks()).toMatchObject({ 'Content hash': 'failed', 'Entry hash': 'failed', 'Hash link': 'passed' })
    await user.click(screen.getByRole('button', { name: 'Open entry 2' }))
    await waitFor(() => expect(screen.getByTestId('entry-verdict')).toHaveTextContent('All present checks passed'))
  })

  it('pages through a long chain and opens a disclosed link', async () => {
    const long = await longChain(1100)
    const user = await verified(await buildPackage(long, [1050], { log: false }))
    expect(screen.getByTestId('verdict')).toHaveTextContent('VALID')
    await user.click(screen.getByRole('tab', { name: 'Chain' }))
    expect(await screen.findByText('Links 1 to 500 of 1100, page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('1100 links, 1 disclosed')).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: 'Chain links' })).getAllByRole('row')).toHaveLength(501)
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Links 501 to 1000 of 1100, page 2 of 3')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Go to sequence'), '1050')
    await user.click(screen.getByRole('button', { name: 'Go' }))
    expect(screen.getByText('Links 1001 to 1100 of 1100, page 3 of 3')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'disclosed' }))
    expect(screen.getByRole('tab', { name: 'Entries' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('region', { name: 'Entry 1050' })).toBeInTheDocument()
  })

  it('shows the log with each signature line and whether it verified', async () => {
    const user = await verified(await buildPackage(chain, [2], { witness: true }), { witness: true })
    await user.click(screen.getByRole('tab', { name: 'Log' }))
    expect(await screen.findByText('ledger.example', { selector: 'dd' })).toBeInTheDocument()
    expect(screen.getAllByText('1 of 1 disclosed entries', { selector: 'dd' })).toHaveLength(2)
    const table = await screen.findByRole('table', { name: 'Signature lines' })
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(3))
    const [, ledger, witness] = within(table).getAllByRole('row')
    expect(within(ledger).getAllByRole('cell').map((cell) => cell.textContent)).toEqual([
      'ledger.example',
      expect.stringMatching(/^[0-9a-f]{8}$/),
      'ledger signature',
      `verified verified with ledger key ${LEDGER_KEY.keyId}`,
    ])
    expect(within(witness).getAllByRole('cell').map((cell) => cell.textContent)).toEqual([
      'witness.example',
      (await witnessNoteKey()).hashHex,
      'cosignature',
      'verified verified with the given witness key, cosigned at 2025-10-09T08:53:20.000Z',
    ])
  })

  it('verifies a signed report and opens the entries it names', async () => {
    const pkg = await buildPackage(chain, [2, 3])
    const user = await verified(pkg)
    await user.click(screen.getByRole('tab', { name: 'Report' }))
    await user.upload(
      screen.getByLabelText('Report file'),
      new File([JSON.stringify(await reportWithEntries(pkg, [2, 3, 4]))], 'report.json'),
    )
    expect(await screen.findByTestId('report-verdict')).toHaveTextContent('VALID')
    expect(screen.getByText('Dispute package')).toBeInTheDocument()
    expect(screen.getByText('PARTLY_SHOWN 1, SHOWN 1')).toBeInTheDocument()
    const timeline = screen.getByRole('article', { name: 'Section Incident timeline' })
    expect(within(timeline).getByText('SHOWN')).toBeInTheDocument()
    expect(within(timeline).getByText('#2 sales-agent tools/call on read_invoice')).toBeInTheDocument()
    expect(within(timeline).getByText('#4')).toBeInTheDocument()
    expect(within(timeline).queryByRole('button', { name: 'Open entry 4' })).not.toBeInTheDocument()
    expect(within(screen.getByRole('article', { name: 'Section Integrity' })).getByText('PARTLY_SHOWN')).toBeInTheDocument()
    await user.click(within(timeline).getByRole('button', { name: 'Open entry 3' }))
    expect(screen.getByRole('tab', { name: 'Entries' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('region', { name: 'Entry 3' })).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Report' }))
    expect(await screen.findByTestId('report-verdict')).toHaveTextContent('VALID')
  })

  it('refuses a report for other evidence', async () => {
    const user = await verified(await buildPackage(chain, [2, 3]))
    await user.click(screen.getByRole('tab', { name: 'Report' }))
    await user.upload(
      screen.getByLabelText('Report file'),
      new File([JSON.stringify(await buildReport(await buildPackage(chain, [2])))], 'report.json'),
    )
    expect(await screen.findByTestId('report-verdict')).toHaveTextContent('INVALID')
    expect(screen.getByText('the report covers other entries than the package discloses')).toBeInTheDocument()
    await user.upload(screen.getByLabelText('Report file'), new File(['nope'], 'broken.json'))
    expect(await screen.findByText('The report is not JSON.')).toBeInTheDocument()
  })

  it('downloads the audit worksheet of the filtered entries and prints', async () => {
    const pkg = (await buildPackage(chain, [1, 2, 3, 4])) as any
    pkg.links[3].entry.target = 'delete_invoice'
    const user = await verified(pkg)
    const blobs: Blob[] = []
    const create = vi.fn((blob: Blob) => {
      blobs.push(blob)
      return 'blob:worksheet'
    })
    const revoke = vi.fn()
    Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      expect(this.download).toBe('audit-worksheet-6.csv')
      expect(this.href).toBe('blob:worksheet')
    })
    await user.click(screen.getByRole('tab', { name: 'Entries' }))
    await user.selectOptions(await screen.findByLabelText('Agent'), 'sales-agent')
    await user.click(screen.getByRole('tab', { name: 'Export' }))
    expect(screen.getByText(/holds the 2 of 4 entries/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Download audit worksheet (CSV)' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Saved audit-worksheet-6.csv with 2 entries.')
    expect(click).toHaveBeenCalledTimes(1)
    const csv = await new Promise<string>((resolve) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.readAsText(blobs[0])
    })
    const lines = csv.trimEnd().split('\r\n')
    expect(lines[0]).toBe(
      'sequence,occurredAt,agentId,principalId,action,target,decision,outcome,model,hash,contentHashCheck,' +
        'entryHashCheck,hashLinkCheck,inclusionProofCheck,statementCheck,receiptCheck',
    )
    expect(lines).toHaveLength(3)
    expect(lines[1]).toMatch(/^2,.*,passed,passed,passed,passed,passed,passed$/)
    expect(lines[2]).toMatch(/^4,.*,delete_invoice,.*,failed,failed,passed,failed,failed,failed$/)
    click.mockRestore()
    const print = vi.fn()
    vi.stubGlobal('print', print)
    await user.click(screen.getByRole('button', { name: 'Print' }))
    expect(print).toHaveBeenCalledTimes(1)
    fireEvent(window, new Event('beforeprint'))
    const view = document.querySelector('.print-view') as HTMLElement
    expect(view).not.toBeNull()
    expect(within(view).getByRole('heading', { name: 'Overview' })).toBeInTheDocument()
    expect(within(view).getByText('2 of 4 entries')).toBeInTheDocument()
    expect(within(view).getAllByRole('row')).toHaveLength(3)
    fireEvent(window, new Event('afterprint'))
    expect(document.querySelector('.print-view')).toBeNull()
    vi.unstubAllGlobals()
  })

  it('reads a dispute package and takes its files', async () => {
    const pkg = await buildPackage(chain, [2, 3], { witness: true })
    const archive = zip({
      'README.txt': 'Nexusphere Ledger dispute package\n',
      'package.json': JSON.stringify(pkg, null, 2),
      'report.json': JSON.stringify(await reportWithEntries(pkg, [2]), null, 2),
      'report.html': '<html></html>',
      'keys.json': JSON.stringify([keyRecord(LEDGER_KEY)]),
      'compliance.json': '{}',
      'witnesses.txt': (await witnessNoteKey()).vkey + '\n',
    }, { deflate: true, descriptor: true })
    const user = userEvent.setup()
    render(<App />)
    await user.upload(screen.getByLabelText('Package file'), new File([archive as Uint8Array<ArrayBuffer>], 'dispute-6.zip'))
    expect(await screen.findByTestId('archive')).toHaveTextContent(
      'Dispute package dispute-6.zip: took package.json, report.json, keys.json, witnesses.txt; ' +
        'did not use README.txt, report.html, compliance.json.',
    )
    expect(screen.getByText('keys.json from the dispute package')).toBeInTheDocument()
    expect(screen.getByLabelText('Witness keys, one per line')).toHaveValue((await witnessNoteKey()).vkey)
    expect(screen.getByLabelText('Required witnesses')).toHaveValue('0')
    await user.type(screen.getByLabelText('Ledger public key'), PINNED)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
    expect(screen.getByText('witness.example')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Report' }))
    expect(await screen.findByText('report.json from the dispute package')).toBeInTheDocument()
    expect(await screen.findByTestId('report-verdict')).toHaveTextContent('VALID')
  })

  it('reports a damaged dispute package', async () => {
    const user = userEvent.setup()
    render(<App />)
    await user.upload(screen.getByLabelText('Package file'), new File([zip({ 'README.txt': 'x' }) as Uint8Array<ArrayBuffer>], 'dispute.zip'))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The dispute package cannot be read: the archive has no package.json.',
    )
    expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled()
  })
})
