import { useEffect, useState, type FormEvent } from 'react'
import type { ReportScope, SignedReport } from '../api/types'
import { useSession } from '../auth/session'
import { Badge, Card, ErrorMessage, useLoad } from '../components/ui'

type Download = { url: string; name: string }

function useDownloads() {
  const [downloads, setDownloads] = useState<Download[]>([])

  useEffect(() => () => downloads.forEach((d) => URL.revokeObjectURL(d.url)), [downloads])

  return [downloads, (blobs: { blob: Blob; name: string }[]) => setDownloads(blobs.map(({ blob, name }) => ({
    url: URL.createObjectURL(blob), name,
  })))] as const
}

function Report({ report }: { report: SignedReport }) {
  return (
    <div className="stack">
      <p className="muted">{report.notice}</p>
      <dl className="summary">
        <dt>Checkpoint</dt><dd>{report.evidence.checkpoint}, {report.evidence.entries} entries</dd>
        <dt>Package verified</dt><dd>{report.evidence.valid ? 'yes' : 'no'}</dd>
        <dt>Profiles</dt><dd>{report.profiles.map((p) => p.id).join(', ')}</dd>
        <dt>Signed by key</dt><dd><code>{report.keyId}</code></dd>
      </dl>
      <table>
        <thead><tr><th>Requirement</th><th>Reference</th><th>Status</th><th>Findings</th></tr></thead>
        <tbody>
          {report.sections.map((section) => (
            <tr key={section.id}>
              <td>{section.requirement}</td>
              <td>{section.reference}</td>
              <td><Badge value={section.status} /></td>
              <td><ul>{section.findings.map((f) => <li key={f}>{f}</li>)}</ul></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function scopeOf(agentId: string, principalId: string): ReportScope {
  return { agentId: agentId || undefined, principalId: principalId || undefined }
}

export function ReportsPage() {
  const { api } = useSession()
  const offered = useLoad(() => api.reports(), [api])
  const [reportId, setReportId] = useState('')
  const [agentId, setAgentId] = useState('')
  const [principalId, setPrincipalId] = useState('')
  const [report, setReport] = useState<SignedReport | null>(null)
  const [reportFiles, setReportFiles] = useDownloads()
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [disputeFiles, setDisputeFiles] = useDownloads()
  const [error, setError] = useState<unknown>(null)
  const chosen = reportId || offered.data?.[0]?.id || ''

  async function createReport(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setReport(null)
    try {
      const scope = scopeOf(agentId, principalId)
      const created = await api.createReport(chosen, scope)
      const html = await api.reportHtml(chosen, scope)
      setReport(created)
      const name = `report-${chosen}-${created.evidence.checkpoint}`
      setReportFiles([
        { blob: new Blob([html], { type: 'text/html' }), name: `${name}.html` },
        { blob: new Blob([JSON.stringify(created, null, 2)], { type: 'application/json' }), name: `${name}.json` },
      ])
    } catch (reason) {
      setError(reason)
    }
  }

  async function createDispute(event: FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      const zip = await api.dispute({ ...scopeOf(agentId, principalId), title, description: description || undefined })
      setDisputeFiles([{ blob: zip, name: 'dispute.zip' }])
    } catch (reason) {
      setError(reason)
    }
  }

  return (
    <>
      <Card title="Compliance reports">
        <p className="muted">Each active compliance profile offers its reports. A report maps the evidence of a
          package to requirements and is signed by the ledger; it is not a certification.</p>
        <form className="filters" onSubmit={createReport}>
          <label>Report
            <select value={chosen} onChange={(e) => setReportId(e.target.value)}>
              {offered.data?.map((r) => (
                <option key={r.id} value={r.id}>{r.title} ({r.profiles.join(', ')})</option>
              ))}
            </select>
          </label>
          <label>Agent<input value={agentId} onChange={(e) => setAgentId(e.target.value)} /></label>
          <label>Principal<input value={principalId} onChange={(e) => setPrincipalId(e.target.value)} /></label>
          <button type="submit" disabled={!chosen}>Create report</button>
        </form>
        {reportFiles.length > 0 && (
          <p>{reportFiles.map((d) => <a key={d.name} href={d.url} download={d.name}>Download {d.name} </a>)}</p>
        )}
        {report && <Report report={report} />}
      </Card>
      <Card title="Dispute package">
        <p className="muted">One archive for an insurer, arbitrator or court: the evidence package, the signed
          dispute report in JSON and HTML, the ledger keys and the compliance profiles. The agent and principal
          above choose the evidence.</p>
        <form className="form" onSubmit={createDispute}>
          <label>Incident<input value={title} required maxLength={200} onChange={(e) => setTitle(e.target.value)} /></label>
          <label className="wide">Description
            <textarea value={description} maxLength={4000} onChange={(e) => setDescription(e.target.value)} />
          </label>
          <div><button type="submit">Create dispute package</button></div>
        </form>
        {disputeFiles.map((d) => <p key={d.name}><a href={d.url} download={d.name}>Download {d.name}</a></p>)}
      </Card>
      <ErrorMessage error={error ?? offered.error} />
    </>
  )
}
