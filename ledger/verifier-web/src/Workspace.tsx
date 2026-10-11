import { useEffect, useMemo, useRef, useState } from 'react'
import type { ChangeEvent, KeyboardEvent, ReactNode } from 'react'
import { flushSync } from 'react-dom'
import { toBase64, verifyReport } from '@nexusphere/ledger'
import type { Json, ReportCheck } from '@nexusphere/ledger'
import {
  FILTER_FIELDS,
  NO_FILTER,
  chainLink,
  checkEntries,
  checkEntry,
  filterEntries,
  filterOptions,
  logSignatures,
  prepareAudit,
  worksheet,
} from './entries'
import type { Audit, AuditEntry, EntryResult, Filter, SignatureLine } from './entries'
import type { Verification } from './verify'

export const PAGE_SIZE = 500

const TABS = [
  ['overview', 'Overview'],
  ['entries', 'Entries'],
  ['chain', 'Chain'],
  ['log', 'Log'],
  ['report', 'Report'],
  ['export', 'Export'],
] as const

type Tab = (typeof TABS)[number][0]

const FILTER_LABELS: Record<(typeof FILTER_FIELDS)[number], string> = {
  agent: 'Agent',
  principal: 'Principal',
  action: 'Action',
  decision: 'Decision',
  outcome: 'Outcome',
}

export interface ReportFile {
  name: string
  text: string
}

interface Props {
  verification: Verification
  overview: ReactNode
  report: ReportFile | null
  onReport: (report: ReportFile) => void
}

export function Workspace({ verification, overview, report, onReport }: Props) {
  const [tab, setTab] = useState<Tab>('overview')
  const [audit, setAudit] = useState<Audit | null>(null)
  const [filter, setFilter] = useState<Filter>(NO_FILTER)
  const [selected, setSelected] = useState<number | null>(null)
  const [printing, setPrinting] = useState(false)
  const tabs = useRef<(HTMLButtonElement | null)[]>([])

  useEffect(() => {
    let current = true
    void prepareAudit(verification.pkg as Json, {
      publicKey: verification.publicKey,
      keyList: verification.keyList as Json[] | undefined,
    }).then((prepared) => {
      if (current) {
        setAudit(prepared)
      }
    })
    return () => {
      current = false
    }
  }, [verification])

  useEffect(() => {
    const before = () => flushSync(() => setPrinting(true))
    const after = () => setPrinting(false)
    window.addEventListener('beforeprint', before)
    window.addEventListener('afterprint', after)
    return () => {
      window.removeEventListener('beforeprint', before)
      window.removeEventListener('afterprint', after)
    }
  }, [])

  const filtered = useMemo(() => (audit ? filterEntries(audit.entries, filter) : []), [audit, filter])

  function open(sequence: number) {
    setSelected(sequence)
    setTab('entries')
  }

  function keyDown(event: KeyboardEvent<HTMLDivElement>) {
    const index = TABS.findIndex(([id]) => id === tab)
    const moves: Record<string, number> = {
      ArrowRight: (index + 1) % TABS.length,
      ArrowLeft: (index + TABS.length - 1) % TABS.length,
      Home: 0,
      End: TABS.length - 1,
    }
    const next = moves[event.key]
    if (next === undefined) {
      return
    }
    event.preventDefault()
    setTab(TABS[next][0])
    tabs.current[next]?.focus()
  }

  return (
    <section className="workspace" aria-label="Auditor workspace">
      <div role="tablist" aria-label="Auditor workspace" className="tabs" onKeyDown={keyDown}>
        {TABS.map(([id, label], index) => (
          <button
            key={id}
            ref={(element) => {
              tabs.current[index] = element
            }}
            type="button"
            role="tab"
            id={`tab-${id}`}
            aria-selected={tab === id}
            aria-controls={`panel-${id}`}
            tabIndex={tab === id ? 0 : -1}
            onClick={() => setTab(id)}
          >
            {label}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`} className="tabpanel screen-only">
        {tab === 'overview' && overview}
        {tab !== 'overview' && !audit && <p className="muted">Reading the package…</p>}
        {tab === 'entries' && audit && (
          <Entries
            audit={audit}
            filter={filter}
            onFilter={setFilter}
            filtered={filtered}
            selected={selected}
            onSelect={setSelected}
          />
        )}
        {tab === 'chain' && audit && <Chain audit={audit} onOpen={open} />}
        {tab === 'log' && audit && <Log audit={audit} verification={verification} />}
        {tab === 'report' && audit && (
          <Report audit={audit} verification={verification} report={report} onReport={onReport} onOpen={open} />
        )}
        {tab === 'export' && audit && (
          <Export audit={audit} filtered={filtered} checkpoint={verification.report.checkpointSequence} />
        )}
      </div>
      {printing && audit && (
        <div className="print-view">
          <h2>Overview</h2>
          {overview}
          <h2>Entries</h2>
          <p>{`${filtered.length} of ${audit.entries.length} entries`}</p>
          <EntryTable entries={filtered} />
        </div>
      )}
    </section>
  )
}

function Entries({
  audit,
  filter,
  onFilter,
  filtered,
  selected,
  onSelect,
}: {
  audit: Audit
  filter: Filter
  onFilter: (filter: Filter) => void
  filtered: AuditEntry[]
  selected: number | null
  onSelect: (sequence: number | null) => void
}) {
  const [page, setPage] = useState(0)
  const options = useMemo(() => filterOptions(audit.entries), [audit])
  const pages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const shown = Math.min(page, pages - 1)

  function change(field: keyof Filter, value: string) {
    onFilter({ ...filter, [field]: value })
    setPage(0)
  }

  return (
    <div className="grid">
      <fieldset className="filters">
        <legend>Filters</legend>
        {FILTER_FIELDS.map((field) => (
          <label key={field}>
            {FILTER_LABELS[field]}
            <select value={filter[field]} onChange={(event) => change(field, event.target.value)}>
              <option value="">Any</option>
              {options[field].map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </label>
        ))}
        <label>
          From
          <input type="date" value={filter.from} onChange={(event) => change('from', event.target.value)} />
        </label>
        <label>
          To
          <input type="date" value={filter.to} onChange={(event) => change('to', event.target.value)} />
        </label>
        <label className="wide">
          Search target and attributes
          <input type="search" value={filter.text} onChange={(event) => change('text', event.target.value)} />
        </label>
        <button type="button" className="secondary" onClick={() => onFilter(NO_FILTER)}>
          Clear filters
        </button>
      </fieldset>
      <p data-testid="entry-count" aria-live="polite">{`${filtered.length} of ${audit.entries.length} entries`}</p>
      <Pager page={shown} pages={pages} total={filtered.length} noun="Entries" onPage={setPage} />
      <EntryTable
        entries={filtered.slice(shown * PAGE_SIZE, (shown + 1) * PAGE_SIZE)}
        selected={selected}
        onSelect={onSelect}
      />
      {selected !== null && <EntryDetail audit={audit} sequence={selected} onClose={() => onSelect(null)} />}
    </div>
  )
}

function EntryTable({
  entries,
  selected,
  onSelect,
}: {
  entries: AuditEntry[]
  selected?: number | null
  onSelect?: (sequence: number) => void
}) {
  if (entries.length === 0) {
    return <p className="muted">No entry matches.</p>
  }
  const modelled = entries.some((entry) => entry.model !== null)
  return (
    <div className="scroll">
      <table>
        <thead>
          <tr>
            <th>Sequence</th>
            <th>Occurred</th>
            <th>Agent</th>
            <th>Principal</th>
            <th>Action</th>
            <th>Target</th>
            <th>Decision</th>
            <th>Outcome</th>
            {modelled && <th>Model</th>}
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr
              key={entry.sequence}
              className={onSelect ? `pick${selected === entry.sequence ? ' chosen' : ''}` : undefined}
              aria-selected={onSelect ? selected === entry.sequence : undefined}
              onClick={onSelect ? () => onSelect(entry.sequence) : undefined}
            >
              <td>
                {onSelect
                  ? (
                    <button type="button" className="link" aria-label={`Open entry ${entry.sequence}`}>
                      {entry.sequence}
                    </button>
                  )
                  : entry.sequence}
              </td>
              <td>{entry.occurredAt}</td>
              <td>{entry.agentId}</td>
              <td>{entry.erased ? <em>erased</em> : entry.principalId}</td>
              <td>{entry.action}</td>
              <td>{entry.erased ? <em>erased</em> : (entry.target ?? '')}</td>
              <td>{entry.decision ?? ''}</td>
              <td>{entry.outcome}</td>
              {modelled && <td>{entry.model ?? ''}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

const FIELD_ORDER = [
  'id',
  'sequence',
  'occurredAt',
  'recordedAt',
  'agentId',
  'principalId',
  'action',
  'target',
  'decision',
  'reason',
  'delegationId',
  'inputHash',
  'outputHash',
  'outcome',
  'correlationId',
  'model',
  'attributes',
  'commitments',
  'salts',
  'previousHash',
  'hash',
]

function fieldsOf(entry: Json, commitments: Record<string, string> | null): [string, string][] {
  const names = [...FIELD_ORDER, ...Object.keys(entry).filter((name) => !FIELD_ORDER.includes(name))]
  const rows: [string, string][] = []
  for (const name of names) {
    const value = name === 'commitments' && !(name in entry) ? commitments : entry[name]
    if (name !== 'commitments' && !(name in entry)) {
      continue
    }
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      const items = Object.entries(value as Json)
      if (items.length === 0) {
        rows.push([name, 'none'])
      }
      for (const [field, item] of items) {
        rows.push([`${name}.${field}`, text(item)])
      }
    } else if (value !== undefined) {
      rows.push([name, text(value)])
    }
  }
  return rows
}

function text(value: unknown): string {
  if (value === null || value === undefined) {
    return 'null'
  }
  return typeof value === 'string' ? value : JSON.stringify(value)
}

function EntryDetail({ audit, sequence, onClose }: { audit: Audit; sequence: number; onClose: () => void }) {
  const [result, setResult] = useState<EntryResult | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  useEffect(() => {
    let current = true
    setResult(null)
    setProblem(null)
    checkEntry(audit, sequence).then(
      (checked) => current && setResult(checked),
      (e: Error) => current && setProblem(e.message),
    )
    return () => {
      current = false
    }
  }, [audit, sequence])

  const position = audit.positions.get(sequence)
  const entry = position === undefined ? null : (audit.links[position].entry as Json | null)
  return (
    <section className="detail" aria-label={`Entry ${sequence}`}>
      <div className="detail-head">
        <h3>{`Entry ${sequence}`}</h3>
        <button type="button" className="secondary" onClick={onClose}>
          Close
        </button>
      </div>
      {problem && <p className="badge bad">{problem}</p>}
      {result && (
        <>
          <p className={`badge ${result.valid ? 'good' : 'bad'}`} data-testid="entry-verdict">
            {result.valid ? 'All present checks passed' : 'A check failed'}
          </p>
          <table className="checks">
            <caption>Checks computed in this browser</caption>
            <thead>
              <tr>
                <th>Check</th>
                <th>Result</th>
                <th>Detail</th>
              </tr>
            </thead>
            <tbody>
              {result.checks.map((check) => (
                <tr key={check.name} data-testid={`check-${check.name}`}>
                  <td>{check.name}</td>
                  <td>
                    <span className={`status ${check.status.replace(' ', '-')}`}>{check.status}</span>
                  </td>
                  <td className="wrap">{check.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      {entry && (
        <dl className="fields">
          {fieldsOf(entry, result?.commitments ?? null).map(([name, value]) => (
            <div key={name}>
              <dt>{name}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  )
}

function Pager({
  page,
  pages,
  total,
  noun,
  onPage,
}: {
  page: number
  pages: number
  total: number
  noun: string
  onPage: (page: number) => void
}) {
  if (pages <= 1) {
    return null
  }
  const first = page * PAGE_SIZE + 1
  const last = Math.min(total, (page + 1) * PAGE_SIZE)
  return (
    <nav className="pager" aria-label={`${noun} pages`}>
      <button type="button" className="secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Previous
      </button>
      <span>{`${noun} ${first} to ${last} of ${total}, page ${page + 1} of ${pages}`}</span>
      <button type="button" className="secondary" disabled={page >= pages - 1} onClick={() => onPage(page + 1)}>
        Next
      </button>
    </nav>
  )
}

function short(hash: string): string {
  return hash.length > 16 ? `${hash.slice(0, 16)}…` : hash
}

function Chain({ audit, onOpen }: { audit: Audit; onOpen: (sequence: number) => void }) {
  const [page, setPage] = useState(0)
  const [jump, setJump] = useState('')
  const total = audit.links.length
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  const rows = []
  for (let index = page * PAGE_SIZE; index < Math.min(total, (page + 1) * PAGE_SIZE); index++) {
    rows.push(chainLink(audit, index))
  }

  function go() {
    const position = audit.positions.get(Number(jump))
    if (position !== undefined) {
      setPage(Math.floor(position / PAGE_SIZE))
    }
  }

  return (
    <div className="grid">
      <dl>
        <div>
          <dt>Starts from</dt>
          <dd>
            {audit.anchorSequence === null
              ? 'the genesis hash, the chain starts at entry 1'
              : `anchor checkpoint ${audit.anchorSequence}, head ${short(audit.startHash)}`}
          </dd>
        </div>
        <div>
          <dt>Ends at</dt>
          <dd>
            {`checkpoint ${audit.checkpointSequence ?? 'unknown'}, head ${short(audit.checkpointHash ?? 'unknown')}`}
          </dd>
        </div>
        <div>
          <dt>Links</dt>
          <dd>{`${total} links, ${audit.entries.length} disclosed`}</dd>
        </div>
      </dl>
      <form
        className="inline"
        onSubmit={(event) => {
          event.preventDefault()
          go()
        }}
      >
        <label>
          Go to sequence
          <input inputMode="numeric" value={jump} onChange={(event) => setJump(event.target.value)} />
        </label>
        <button type="submit" className="secondary">
          Go
        </button>
      </form>
      <Pager page={page} pages={pages} total={total} noun="Links" onPage={setPage} />
      <div className="scroll">
        <table aria-label="Chain links">
          <thead>
            <tr>
              <th>Sequence</th>
              <th>Hash</th>
              <th>Previous hash</th>
              <th>Disclosed</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((link) => (
              <tr key={link.sequence}>
                <td>{link.sequence}</td>
                <td className="mono" title={link.hash}>{short(link.hash)}</td>
                <td className="mono" title={link.previousHash}>{short(link.previousHash)}</td>
                <td>
                  {link.disclosed
                    ? (
                      <button type="button" className="link" onClick={() => onOpen(link.sequence)}>
                        disclosed
                      </button>
                    )
                    : 'hash only'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function Log({ audit, verification }: { audit: Audit; verification: Verification }) {
  const [lines, setLines] = useState<SignatureLine[] | null>(null)
  const { report, timestampPinned, rekorPinned } = verification

  useEffect(() => {
    let current = true
    void logSignatures(audit, verification.witnessKeys).then((found) => current && setLines(found))
    return () => {
      current = false
    }
  }, [audit, verification])

  const checkpoint = (verification.pkg.checkpoint ?? {}) as Json
  const rekor = (checkpoint.rekor ?? null) as Json | null
  const note = audit.note
  const rows: [string, string][] = note
    ? [
        ['Origin', note.checkpoint.origin],
        ['Tree size', String(note.checkpoint.size)],
        ['Root', toBase64(note.checkpoint.root)],
        ['Inclusion proofs', `${report.provenEntries} of ${report.disclosedEntries} disclosed entries`],
        ['Receipts', `${report.receiptedEntries} of ${report.disclosedEntries} disclosed entries`],
      ]
    : [['Log', audit.noteProblem ? `the log checkpoint cannot be read: ${audit.noteProblem}` : 'the package has no log']]
  rows.push(['Timestamp', report.timestampedAt
    ? `${report.timestampedAt} by ${report.timestampAuthority}${timestampPinned ? ' (pinned)' : ', authority not pinned'}`
    : typeof checkpoint.timestamp === 'string' ? 'present, not valid' : 'none'])
  rows.push(['Rekor', report.rekorLogIndex != null
    ? `entry ${report.rekorLogIndex} in ${report.rekorOrigin}${rekorPinned ? ' (pinned)' : ', log not pinned'}`
    : rekor ? `entry ${text(rekor.logIndex)}, not verified` : 'none'])
  if (rekor && typeof rekor.integratedTime !== 'undefined') {
    rows.push(['Rekor integrated time', text(rekor.integratedTime)])
  }
  return (
    <div className="grid">
      <dl>
        {rows.map(([name, value]) => (
          <div key={name}>
            <dt>{name}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      {note && (
        <div className="scroll">
          <table aria-label="Signature lines">
            <thead>
              <tr>
                <th>Name</th>
                <th>Key hash</th>
                <th>Kind</th>
                <th>Result</th>
              </tr>
            </thead>
            <tbody>
              {(lines ?? []).map((line, index) => (
                <tr key={`${line.name}-${index}`}>
                  <td>{line.name}</td>
                  <td className="mono">{line.keyHash}</td>
                  <td>{line.kind}</td>
                  <td className="wrap">
                    <span className={`status ${line.verified ? 'passed' : 'failed'}`}>
                      {line.verified ? 'verified' : 'not verified'}
                    </span>{' '}
                    {line.detail}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

interface Section {
  id: string
  reference: string
  requirement: string
  status: string
  findings: string[]
  entries: number[]
}

function sectionsOf(document: unknown): Section[] {
  const sections = document && typeof document === 'object' ? (document as Json).sections : null
  return (Array.isArray(sections) ? sections : []).map((section: Json) => ({
    id: text(section?.id),
    reference: typeof section?.reference === 'string' ? section.reference : text(section?.id),
    requirement: typeof section?.requirement === 'string' ? section.requirement : '',
    status: text(section?.status),
    findings: Array.isArray(section?.findings) ? section.findings.map(text) : [],
    entries: Array.isArray(section?.entries) ? section.entries.filter((item: unknown) => typeof item === 'number') : [],
  }))
}

function Report({
  audit,
  verification,
  report,
  onReport,
  onOpen,
}: {
  audit: Audit
  verification: Verification
  report: ReportFile | null
  onReport: (report: ReportFile) => void
  onOpen: (sequence: number) => void
}) {
  const [check, setCheck] = useState<ReportCheck | null>(null)
  const [document, setDocument] = useState<unknown>(null)
  const [problem, setProblem] = useState<string | null>(null)

  useEffect(() => {
    let current = true
    setCheck(null)
    setProblem(null)
    setDocument(null)
    if (!report) {
      return
    }
    let parsed: unknown
    try {
      parsed = JSON.parse(report.text)
    } catch {
      setProblem('The report is not JSON.')
      return
    }
    setDocument(parsed)
    void verifyReport(parsed, verification.pkg, {
      publicKey: verification.publicKey,
      keys: (verification.keyList ?? null) as Json[] | null,
    }).then((result) => current && setCheck(result))
    return () => {
      current = false
    }
  }, [report, verification])

  async function choose(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (file) {
      onReport({ name: file.name, text: await file.text() })
    }
  }

  const subject = document && typeof document === 'object' ? (document as Json).subject : null
  const subjectTitle = subject && typeof subject === 'object' ? (subject as Json).title : null
  return (
    <div className="grid">
      <label className="drop small">
        <span>{report ? report.name : 'Choose a signed report (JSON, nexusphere-ledger/report/v1) to check against this package'}</span>
        <input type="file" accept="application/json,.json" aria-label="Report file" onChange={(event) => void choose(event)} />
      </label>
      {problem && <p className="badge bad">{problem}</p>}
      {check && (
        <>
          <p className={`badge ${check.valid ? 'good' : 'bad'}`} data-testid="report-verdict">
            {check.valid ? 'VALID' : 'INVALID'}
          </p>
          {check.problems.length > 0 && (
            <ul className="problems">
              {check.problems.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          )}
          <dl>
            {([
              ['Title', check.title ?? 'none'],
              ['Subject', typeof subjectTitle === 'string' ? subjectTitle : 'none'],
              ['Report', check.report ?? 'unknown'],
              ['Signed by', check.keyId ?? 'unknown'],
              ['Evidence', `checkpoint ${check.checkpointSequence ?? 'unknown'}, ${check.entries ?? 'unknown'} entries`],
              ['Sections', Object.entries(check.statuses).map(([status, count]) => `${status} ${count}`).join(', ') || 'none'],
            ] as [string, string][]).map(([name, value]) => (
              <div key={name}>
                <dt>{name}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
          {sectionsOf(document).map((section) => (
            <article key={section.id} className="section" aria-label={`Section ${section.reference}`}>
              <div className="detail-head">
                <h3>{section.reference}</h3>
                <span className={`status ${statusClass(section.status)}`}>{section.status}</span>
              </div>
              {section.requirement && <p className="muted">{section.requirement}</p>}
              {section.findings.length > 0 && (
                <ul>
                  {section.findings.map((finding, index) => (
                    <li key={index}>{finding}</li>
                  ))}
                </ul>
              )}
              {section.entries.length > 0 && (
                <p className="sequences">
                  Entries:{' '}
                  {section.entries.map((sequence) =>
                    audit.positions.has(sequence) && audit.links[audit.positions.get(sequence)!].entry
                      ? (
                        <button
                          key={sequence}
                          type="button"
                          className="link"
                          aria-label={`Open entry ${sequence}`}
                          onClick={() => onOpen(sequence)}
                        >
                          {`#${sequence}`}
                        </button>
                      )
                      : <span key={sequence}>{`#${sequence}`}</span>,
                  )}
                </p>
              )}
            </article>
          ))}
        </>
      )}
    </div>
  )
}

function statusClass(status: string): string {
  return status === 'SHOWN' ? 'passed' : status === 'PARTLY_SHOWN' ? 'partly' : 'failed'
}

function Export({ audit, filtered, checkpoint }: { audit: Audit; filtered: AuditEntry[]; checkpoint: number }) {
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState<string | null>(null)

  async function download() {
    setBusy(true)
    setDone(null)
    try {
      const results = await checkEntries(audit, filtered.map((entry) => entry.sequence))
      const blob = new Blob([worksheet(filtered, results)], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const name = `audit-worksheet-${checkpoint}.csv`
      const link = window.document.createElement('a')
      link.href = url
      link.download = name
      window.document.body.append(link)
      link.click()
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 10000)
      setDone(`${name} with ${filtered.length} entries`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="grid">
      <p>
        {`The worksheet holds the ${filtered.length} of ${audit.entries.length} entries the filters of the Entries tab show, ` +
          'with the result of each check computed in this browser. It is made on this page; nothing is sent.'}
      </p>
      <div className="actions">
        <button type="button" disabled={busy} onClick={() => void download()}>
          {busy ? 'Checking entries…' : 'Download audit worksheet (CSV)'}
        </button>
        <button type="button" className="secondary" onClick={() => window.print()}>
          Print
        </button>
      </div>
      {done && <p role="status">{`Saved ${done}.`}</p>}
    </div>
  )
}
