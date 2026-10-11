import { useState } from 'react'
import type { Anomaly } from '../api/types'
import { useSession } from '../auth/session'
import { Badge, Card, ErrorMessage, Time, useLoad } from '../components/ui'

const STATUSES = ['OPEN', 'RESOLVED']

function amount(anomaly: Anomaly, value: number) {
  if (anomaly.kind === 'VOLUME') {
    return Number.isInteger(value) ? String(value) : value.toFixed(1)
  }
  return `${(value * 100).toFixed(1)}%`
}

export function AnomaliesPage() {
  const { api } = useSession()
  const [status, setStatus] = useState('OPEN')
  const anomalies = useLoad(() => api.anomalies({ status: status || undefined }), [api, status])
  const [error, setError] = useState<unknown>(null)
  const [checking, setChecking] = useState(false)

  async function check() {
    setError(null)
    setChecking(true)
    try {
      await api.detectAnomalies()
      anomalies.reload()
    } catch (failure) {
      setError(failure)
    } finally {
      setChecking(false)
    }
  }

  return (
    <Card title="Anomalies" actions={<>
      <label>Status{' '}
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
          <option value="">All</option>
        </select>
      </label>
      <button type="button" disabled={checking} onClick={check}>Check now</button>
    </>}>
      <p className="muted">The ledger compares each agent's entry volume, share of denials and share of failures in the
        latest window with its own history and opens an anomaly when they stray too far.</p>
      <ErrorMessage error={anomalies.error ?? error} />
      {anomalies.data && (anomalies.data.items.length === 0 ? <p className="muted">No anomalies.</p> : (
        <table>
          <thead>
            <tr>
              <th>Agent</th>
              <th>Kind</th>
              <th>Observed</th>
              <th>Expected</th>
              <th>Score</th>
              <th>Window</th>
              <th>Status</th>
              <th>Detected</th>
              <th>Resolved</th>
            </tr>
          </thead>
          <tbody>
            {anomalies.data.items.map((a) => (
              <tr key={a.id}>
                <td>{a.agentId}</td>
                <td><Badge value={a.kind} /></td>
                <td>{amount(a, a.observed)}</td>
                <td>{amount(a, a.expected)}</td>
                <td>{a.score.toFixed(1)}</td>
                <td><Time value={a.windowStart} /> – <Time value={a.windowEnd} /></td>
                <td><Badge value={a.status} /></td>
                <td><Time value={a.detectedAt} /></td>
                <td><Time value={a.resolvedAt} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
    </Card>
  )
}
