import { NoteKey, PrivateKey } from '@nexusphere/ledger'
import type { Json } from '@nexusphere/ledger'
import {
  LEDGER_KEY,
  WITNESS,
  buildPackage,
  entries,
  erase,
  witnessNoteKey,
} from '../../sdk/typescript/test/support'
import {
  NO_FILTER,
  WORKSHEET_COLUMNS,
  chainLink,
  checkEntries,
  checkEntry,
  filterEntries,
  filterOptions,
  logSignatures,
  prepareAudit,
  worksheet,
} from './entries'
import type { EntryResult } from './entries'

const PINNED = LEDGER_KEY.publicKey.encoded

function statuses(result: EntryResult): Record<string, string> {
  return Object.fromEntries(result.checks.map((check) => [check.name, check.status]))
}

const ALL_PASSED = {
  'Content hash': 'passed',
  'Entry hash': 'passed',
  'Hash link': 'passed',
  'Inclusion proof': 'passed',
  'SCITT statement': 'passed',
  'SCITT receipt': 'passed',
}

describe('entries', async () => {
  const chain = await entries(6)

  it('passes every check of an untouched entry', async () => {
    const audit = await prepareAudit(await buildPackage(chain, [2, 3, 5]), { publicKey: PINNED })
    expect(audit.entries.map((entry) => entry.sequence)).toEqual([2, 3, 5])
    const result = await checkEntry(audit, 3)
    expect(statuses(result)).toEqual(ALL_PASSED)
    expect(result.valid).toBe(true)
    expect(Object.keys(result.commitments!)).toContain('attributes.tool')
    expect(result.checks.find((check) => check.name === 'Hash link')!.detail).toBe('the previous hash is the hash of sequence 2')
  })

  it('links the first entry to the genesis hash or the anchor', async () => {
    const plain = await prepareAudit(await buildPackage(chain, [1]))
    expect((await checkEntry(plain, 1)).checks[2].detail).toBe('the previous hash is the genesis hash')
    const anchored = await prepareAudit(await buildPackage(chain, [4], { anchor: 3 }))
    expect(anchored.firstSequence).toBe(4)
    const result = await checkEntry(anchored, 4)
    expect(statuses(result)).toEqual(ALL_PASSED)
    expect(result.checks[2].detail).toBe('the previous hash is the head of anchor checkpoint 3')
  })

  it('fails the hash checks, the proof and the statement of a tampered entry', async () => {
    const pkg = (await buildPackage(chain, [2, 3])) as any
    pkg.links[2].entry.target = 'delete_invoice'
    const audit = await prepareAudit(pkg, { publicKey: PINNED })
    const result = await checkEntry(audit, 3)
    expect(statuses(result)).toEqual({
      'Content hash': 'failed',
      'Entry hash': 'failed',
      'Hash link': 'passed',
      'Inclusion proof': 'failed',
      'SCITT statement': 'failed',
      'SCITT receipt': 'failed',
    })
    expect(result.valid).toBe(false)
    expect(result.checks[0].detail).toMatch(/^recomputed [0-9a-f]{64}, the link has [0-9a-f]{64}$/)
    expect((await checkEntry(audit, 2)).valid).toBe(true)
  })

  it('fails the hash link of a link whose previous hash was changed', async () => {
    const pkg = (await buildPackage(chain, [2, 3])) as any
    pkg.links[2].previousHash = '1'.repeat(64)
    const audit = await prepareAudit(pkg)
    const result = await checkEntry(audit, 3)
    expect(statuses(result)['Hash link']).toBe('failed')
    expect(statuses(result)['Entry hash']).toBe('failed')
  })

  it('shows the log checks as not present without a log', async () => {
    const audit = await prepareAudit(await buildPackage(chain, [2], { log: false }))
    expect(statuses(await checkEntry(audit, 2))).toEqual({
      ...ALL_PASSED,
      'Inclusion proof': 'not present',
      'SCITT statement': 'not present',
      'SCITT receipt': 'not present',
    })
  })

  it('fails the statement and receipt signed by a key the pinned key does not reach', async () => {
    const other = await PrivateKey.generate()
    const audit = await prepareAudit(await buildPackage(chain, [2]), { publicKey: other.publicKey.encoded })
    const result = await checkEntry(audit, 2)
    expect(statuses(result)['SCITT statement']).toBe('failed')
    expect(statuses(result)['SCITT receipt']).toBe('failed')
    expect(statuses(result)['Inclusion proof']).toBe('passed')
  })

  it('keeps the commitments of an erased entry', async () => {
    const pkg = (await buildPackage(chain, [2])) as any
    pkg.links[1].entry = await erase(chain[1])
    const audit = await prepareAudit(pkg)
    expect(audit.entries[0].erased).toBe(true)
    const result = await checkEntry(audit, 2)
    expect(result.valid).toBe(true)
    expect(result.commitments).toEqual(pkg.links[1].entry.commitments)
  })

  it('refuses a sequence that is not disclosed', async () => {
    const audit = await prepareAudit(await buildPackage(chain, [2]))
    await expect(checkEntry(audit, 3)).rejects.toThrow('sequence 3 is not disclosed in the package')
    expect(chainLink(audit, 2)).toEqual({ sequence: 3, previousHash: chain[1].hash, hash: chain[2].hash, disclosed: false })
  })

  it('filters by field, date and text', async () => {
    const tagged = await entries(4, undefined, undefined, {}, { 3: { invoice: 'INV-4711' } })
    const days = tagged.map((entry, index) => ({ ...entry, occurredAt: `2026-10-0${index + 1}T08:00:00.123456Z` }))
    const audit = await prepareAudit({ links: days.map((entry) => ({ ...entry, entry })) } as Json)
    expect(filterOptions(audit.entries)).toEqual({
      agent: ['invoice-agent', 'sales-agent'],
      principal: ['acme', 'globex'],
      action: ['tools/call'],
      decision: ['ALLOW'],
      outcome: ['SUCCEEDED'],
    })
    const sequences = (filter: Partial<typeof NO_FILTER>) =>
      filterEntries(audit.entries, { ...NO_FILTER, ...filter }).map((entry) => entry.sequence)
    expect(sequences({})).toEqual([1, 2, 3, 4])
    expect(sequences({ agent: 'sales-agent' })).toEqual([2, 4])
    expect(sequences({ principal: 'acme', outcome: 'SUCCEEDED' })).toEqual([1, 3])
    expect(sequences({ from: '2026-10-02', to: '2026-10-03' })).toEqual([2, 3])
    expect(sequences({ text: 'inv-4711' })).toEqual([3])
    expect(sequences({ text: 'READ_INVOICE' })).toEqual([1, 2, 3, 4])
    expect(sequences({ agent: 'sales-agent', text: 'INV-4711' })).toEqual([])
  })

  it('writes the worksheet with the check results', async () => {
    const pkg = (await buildPackage(chain, [2, 3])) as any
    pkg.links[2].entry.target = '=HYPERLINK("x")'
    const audit = await prepareAudit(pkg)
    const results = await checkEntries(audit, [2, 3])
    const lines = worksheet(audit.entries, results).trimEnd().split('\r\n')
    expect(lines[0]).toBe(WORKSHEET_COLUMNS.join(','))
    expect(lines[0]).toBe(
      'sequence,occurredAt,agentId,principalId,action,target,decision,outcome,model,hash,contentHashCheck,' +
        'entryHashCheck,hashLinkCheck,inclusionProofCheck,statementCheck,receiptCheck',
    )
    expect(lines[1]).toBe(
      `2,2026-10-01T08:00:01.123456Z,sales-agent,globex,tools/call,read_invoice,ALLOW,SUCCEEDED,,${chain[1].hash},` +
        'passed,passed,passed,passed,passed,passed',
    )
    expect(lines[2]).toContain(',"\'=HYPERLINK(""x"")",')
    expect(lines[2]).toMatch(/,failed,failed,passed,failed,failed,failed$/)
  })

  it('verifies each signature line of the log checkpoint', async () => {
    const audit = await prepareAudit(await buildPackage(chain, [2], { witness: true }), { publicKey: PINNED })
    const witness = await witnessNoteKey()
    const lines = await logSignatures(audit, [witness.vkey])
    expect(lines.map(({ name, kind, verified }) => ({ name, kind, verified }))).toEqual([
      { name: 'ledger.example', kind: 'ledger signature', verified: true },
      { name: WITNESS, kind: 'cosignature', verified: true },
    ])
    expect(lines[1].detail).toBe(`verified with the given witness key, cosigned at ${new Date(1760000000 * 1000).toISOString()}`)
    expect(lines[1].keyHash).toBe(witness.hashHex)
    const unknown = await logSignatures(audit, [])
    expect(unknown[1]).toMatchObject({ verified: false, detail: 'no witness key given for this name' })
    const stranger = await NoteKey.of(WITNESS, 0x04, (await PrivateKey.generate()).publicKey)
    expect((await logSignatures(audit, [stranger.vkey, 'not a key']))[1]).toMatchObject({
      verified: false,
      detail: 'does not verify with the given witness key',
    })
  })

  it('names a log checkpoint that cannot be read', async () => {
    const pkg = (await buildPackage(chain, [2])) as any
    pkg.log.checkpoint = 'broken'
    const audit = await prepareAudit(pkg)
    const result = await checkEntry(audit, 2)
    expect(result.checks[3]).toMatchObject({ status: 'failed' })
    expect(result.checks[3].detail).toMatch(/^the log checkpoint cannot be read/)
    expect(await logSignatures(audit, [])).toEqual([])
  })
})
