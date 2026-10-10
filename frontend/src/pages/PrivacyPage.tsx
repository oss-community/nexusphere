import { useState, type FormEvent } from 'react'
import type { Erasure, RetentionSweep } from '../api/types'
import { useSession } from '../auth/session'
import { Card, ErrorMessage, Time, useLoad } from '../components/ui'

export function PrivacyPage() {
  const { api } = useSession()
  const holds = useLoad(() => api.legalHolds(true), [api])
  const [erasePrincipal, setErasePrincipal] = useState('')
  const [eraseReason, setEraseReason] = useState('')
  const [erasure, setErasure] = useState<Erasure | null>(null)
  const [holdPrincipal, setHoldPrincipal] = useState('')
  const [holdReason, setHoldReason] = useState('')
  const [sweep, setSweep] = useState<RetentionSweep | null>(null)
  const [error, setError] = useState<unknown>(null)

  async function run(action: () => Promise<void>) {
    setError(null)
    try {
      await action()
    } catch (reason) {
      setError(reason)
    }
  }

  function erase(event: FormEvent) {
    event.preventDefault()
    run(async () => {
      setErasure(await api.erasePrincipal(erasePrincipal, eraseReason || undefined))
      setErasePrincipal('')
      setEraseReason('')
    })
  }

  function place(event: FormEvent) {
    event.preventDefault()
    run(async () => {
      await api.placeLegalHold(holdPrincipal, holdReason)
      setHoldPrincipal('')
      setHoldReason('')
      holds.reload()
    })
  }

  function release(id: string) {
    const reason = window.prompt('Why is the hold released?')
    if (reason) {
      run(async () => {
        await api.releaseLegalHold(id, reason)
        holds.reload()
      })
    }
  }

  return (
    <>
      <ErrorMessage error={error} />
      <Card title="Erase a principal">
        <form className="form" onSubmit={erase}>
          <label>Principal<input value={erasePrincipal} onChange={(e) => setErasePrincipal(e.target.value)} required /></label>
          <label>Reason<input value={eraseReason} onChange={(e) => setEraseReason(e.target.value)} /></label>
          <div className="wide"><button type="submit" className="danger">Erase</button></div>
        </form>
        {erasure && (
          <div className={erasure.completed ? 'report ok' : 'report'} role="status">
            {erasure.erasedEntries} entries erased, {erasure.retainedEntries} retained
            {erasure.legalHold && ' under a legal hold'}
            {erasure.retainedUntil && <> until <Time value={erasure.retainedUntil} /></>}
            {erasure.completed ? '; the erasure is complete.' : '; the rest is erased when retention ends.'}
          </div>
        )}
      </Card>
      <Card title="Legal holds">
        <form className="form" onSubmit={place}>
          <label>Principal<input value={holdPrincipal} onChange={(e) => setHoldPrincipal(e.target.value)} required /></label>
          <label>Reason<input value={holdReason} onChange={(e) => setHoldReason(e.target.value)} required /></label>
          <div className="wide"><button type="submit">Place hold</button></div>
        </form>
        <ErrorMessage error={holds.error} />
        {holds.data && (holds.data.items.length === 0 ? <p className="muted">No active legal holds.</p> : (
          <table>
            <thead><tr><th>Principal</th><th>Reason</th><th>Placed</th><th /></tr></thead>
            <tbody>
              {holds.data.items.map((h) => (
                <tr key={h.id}>
                  <td>{h.principalId ?? h.principalRef}</td>
                  <td>{h.reason}</td>
                  <td><Time value={h.placedAt} /></td>
                  <td className="row-actions">
                    <button type="button" className="secondary" onClick={() => release(h.id)}>Release</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}
      </Card>
      <Card title="Retention">
        <p className="muted">The ledger erases what the compliance profiles no longer let it keep on its own schedule.</p>
        <button type="button" onClick={() => run(async () => setSweep(await api.sweepRetention()))}>Run now</button>
        {sweep && (
          <div className="report ok" role="status">
            {sweep.expiredEntries} entries expired, {sweep.erasures.length} erasures continued.
          </div>
        )}
      </Card>
    </>
  )
}
