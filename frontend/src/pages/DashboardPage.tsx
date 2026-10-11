import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import type { Activity, ActivityCounts, Evidence, Grant } from '../api/types'
import { useSession } from '../auth/session'
import { ApprovalTable } from '../components/ApprovalTable'
import { Badge, Card, ErrorMessage, Time, useLoad } from '../components/ui'

const PERIODS = [7, 30, 90]
const DAY = 24 * 60 * 60 * 1000
const WEEK = 7 * DAY

function periodDays(activity: Activity, days: number): (ActivityCounts & { day: string })[] {
  const counts = new Map(activity.days.map((d) => [d.day, d]))
  const start = Date.parse(`${activity.since.slice(0, 10)}T00:00:00Z`)
  return Array.from({ length: days }, (_, index) => {
    const day = new Date(start + index * DAY).toISOString().slice(0, 10)
    return counts.get(day) ?? { day, total: 0, allowed: 0, denied: 0, failed: 0 }
  })
}

function describe(day: string, c: ActivityCounts) {
  return `${day}: ${c.total} actions, ${c.allowed} allowed, ${c.denied} denied, ${c.failed} failed`
}

function ActivityCard({ activity, days, onDays }: {
  activity: Activity | null
  days: number
  onDays: (days: number) => void
}) {
  const series = activity ? periodDays(activity, days) : []
  const max = Math.max(0, ...series.map((d) => d.total))
  const scale = Math.max(1, max)
  return (
    <Card title="Activity" actions={
      <div className="segmented" role="group" aria-label="Period">
        {PERIODS.map((p) => (
          <button key={p} type="button" className={p === days ? '' : 'secondary'} aria-pressed={p === days}
            onClick={() => onDays(p)}>{p} days</button>
        ))}
      </div>
    }>
      {activity && (
        <>
          <dl className="totals">
            <div><dt>Actions</dt><dd>{activity.total}</dd></div>
            <div><dt>Allowed</dt><dd>{activity.allowed}</dd></div>
            <div><dt>Denied</dt><dd>{activity.denied}</dd></div>
            <div><dt>Failed</dt><dd>{activity.failed}</dd></div>
          </dl>
          <ol className="bars" aria-label={`Actions per day, last ${days} days (UTC)`}>
            {series.map((d) => (
              <li key={d.day} className="bar" title={describe(d.day, d)} aria-label={describe(d.day, d)}>
                <span style={{ height: `${(d.total / scale) * 100}%` }} />
              </li>
            ))}
          </ol>
          <div className="bars-axis muted">
            <span>{series[0]?.day}</span>
            <span>busiest day: {max} actions</span>
            <span>{series[series.length - 1]?.day}</span>
          </div>
          {activity.agents.length === 0
            ? <p className="muted">No agent acted for you in this period.</p>
            : (
              <table>
                <thead>
                  <tr><th>Agent</th><th>Actions</th><th>Allowed</th><th>Denied</th><th>Failed</th><th>Last action</th></tr>
                </thead>
                <tbody>
                  {activity.agents.map((a) => (
                    <tr key={a.agentId}>
                      <td>{a.agentId}</td>
                      <td>{a.total}</td>
                      <td>{a.allowed}</td>
                      <td>{a.denied}</td>
                      <td>{a.failed}</td>
                      <td><Time value={a.lastAt} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
        </>
      )}
    </Card>
  )
}

function GrantsCard({ grants }: { grants: Grant[] | null }) {
  const now = Date.now()
  const active = (grants ?? []).filter((g) => g.status === 'ACTIVE')
  const expiring = active.filter((g) => Date.parse(g.expiresAt) - now <= WEEK)
    .sort((a, b) => a.expiresAt.localeCompare(b.expiresAt))
  return (
    <Card title="Grants" actions={<Link to="/my/grants">My grants</Link>}>
      {grants && (
        <>
          <p><strong>{active.length}</strong> active {active.length === 1 ? 'grant' : 'grants'}, {expiring.length}{' '}
            expiring within 7 days.</p>
          {expiring.length > 0 && (
            <ul className="plain">
              {expiring.map((g) => (
                <li key={g.id}>{g.agentId}: {g.actions.join(', ')} on {g.targets.join(', ')}, expires{' '}
                  <Time value={g.expiresAt} /></li>
              ))}
            </ul>
          )}
        </>
      )}
    </Card>
  )
}

function Timeline({ items }: { items: Evidence[] }) {
  if (items.length === 0) {
    return <p className="muted">No actions recorded about you yet.</p>
  }
  const sorted = [...items].sort((a, b) => b.sequence - a.sequence)
  const groups: { day: string; items: Evidence[] }[] = []
  sorted.forEach((e) => {
    const day = e.recordedAt.slice(0, 10)
    const last = groups[groups.length - 1]
    if (last && last.day === day) {
      last.items.push(e)
    } else {
      groups.push({ day, items: [e] })
    }
  })
  return (
    <div className="timeline">
      <p className="muted">Newest first, days and times in UTC.</p>
      {groups.map((g) => (
        <section key={g.day} aria-label={g.day}>
          <h3>{g.day}</h3>
          <ol>
            {g.items.map((e) => (
              <li key={e.id}>
                <time dateTime={e.recordedAt} title={e.recordedAt}>{e.recordedAt.slice(11, 16)}</time>
                <span><strong>{e.agentId}</strong> {e.action}
                  {' '}{e.erased ? <em>erased</em> : e.target && <code>{e.target}</code>}</span>
                <span className="badges"><Badge value={e.decision} /> <Badge value={e.outcome} /></span>
              </li>
            ))}
          </ol>
        </section>
      ))}
    </div>
  )
}

function ExportCard({ agents }: { agents: string[] }) {
  const { api } = useSession()
  const [agentId, setAgentId] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [download, setDownload] = useState<{ url: string; name: string } | null>(null)

  useEffect(() => () => {
    if (download) {
      URL.revokeObjectURL(download.url)
    }
  }, [download])

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const pkg = await api.exportMyPackage({
        agentId: agentId || undefined,
        fromSequence: from ? Number(from) : undefined,
        toSequence: to ? Number(to) : undefined,
      })
      const name = `evidence-package-${pkg.checkpoint?.sequence ?? 'latest'}.json`
      const url = URL.createObjectURL(new Blob([JSON.stringify(pkg, null, 2)], { type: 'application/json' }))
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = name
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      setDownload({ url, name })
    } catch (reason) {
      setError(reason)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title="Export my evidence">
      <form className="filters" onSubmit={submit}>
        <label>Agent
          <select value={agentId} onChange={(e) => setAgentId(e.target.value)}>
            <option value="">All agents</option>
            {agents.map((a) => <option key={a} value={a}>{a}</option>)}
          </select>
        </label>
        <label>From sequence<input type="number" min={1} value={from} onChange={(e) => setFrom(e.target.value)} /></label>
        <label>To sequence<input type="number" min={1} value={to} onChange={(e) => setTo(e.target.value)} /></label>
        <button type="submit" disabled={busy}>Export</button>
      </form>
      <ErrorMessage error={error} />
      {download && <p>Saved <a href={download.url} download={download.name}>{download.name}</a>.</p>}
      <p className="muted">The package holds the evidence about you with its hash chain and the signed checkpoint. It
        can be verified offline in the Auditor Portal (ledger/verifier-web) with the ledger's public key, without
        trusting this ledger.</p>
    </Card>
  )
}

export function DashboardPage() {
  const { api } = useSession()
  const [days, setDays] = useState(30)
  const activity = useLoad(() => api.myActivity(days), [api, days])
  const approvals = useLoad(() => api.myApprovals('PENDING'), [api])
  const grants = useLoad(() => api.myGrants(), [api])
  const [timeline, setTimeline] = useState<Evidence[] | null>(null)
  const [next, setNext] = useState<number | null>(null)
  const [error, setError] = useState<unknown>(null)

  const loadEvidence = useCallback(async (before?: number) => {
    setError(null)
    try {
      const page = await api.myRecentEvidence(before)
      setTimeline((current) => (before !== undefined && current ? [...current, ...page.items] : page.items))
      setNext(typeof page.nextBefore === 'number' ? page.nextBefore : null)
    } catch (reason) {
      setError(reason)
    }
  }, [api])

  useEffect(() => {
    loadEvidence()
  }, [loadEvidence])

  async function answer(question: string, action: (reason?: string) => Promise<unknown>) {
    const reason = window.prompt(question)
    if (reason === null) {
      return
    }
    setError(null)
    try {
      await action(reason || undefined)
      approvals.reload()
      loadEvidence()
    } catch (failure) {
      setError(failure)
    }
  }

  const pending = (approvals.data?.items ?? []).filter((a) => a.status === 'PENDING' && a.approver !== 'operator')

  return (
    <>
      <ErrorMessage error={activity.error ?? approvals.error ?? grants.error ?? error} />
      <ActivityCard activity={activity.data} days={days} onDays={setDays} />
      <Card title={`Waiting for you (${pending.length})`} actions={<Link to="/my/approvals">My approvals</Link>}>
        <ApprovalTable items={pending} showPrincipal={false} actions={(a) => <>
          <button type="button" onClick={() => answer('Reason for approving (optional)',
            (reason) => api.approveMyApproval(a.id, reason))}>Approve</button>
          <button type="button" className="danger" onClick={() => answer('Reason for denying (optional)',
            (reason) => api.denyMyApproval(a.id, reason))}>Deny</button>
        </>} />
      </Card>
      <div className="dashboard-row">
        <GrantsCard grants={grants.data?.items ?? null} />
        <ExportCard agents={(activity.data?.agents ?? []).map((a) => a.agentId)} />
      </div>
      <Card title="Recent actions" actions={<>
        <Link to="/my/ask">Ask a question</Link>
        <Link to="/my/evidence">Evidence about me</Link>
      </>}>
        {timeline && <Timeline items={timeline} />}
        {next !== null && (
          <button type="button" className="secondary" onClick={() => loadEvidence(next)}>Load more</button>
        )}
      </Card>
    </>
  )
}
