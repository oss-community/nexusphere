import { useState } from 'react'
import type { VerificationReport } from '../api/types'
import { useSession } from '../auth/session'
import { Badge, Card, ErrorMessage, Hash, Time, useLoad } from '../components/ui'

export function Overview() {
  const { api } = useSession()
  const state = useLoad(async () => {
    const [head, keys] = await Promise.all([api.head(), api.keys()])
    const checkpoint = await api.latestCheckpoint().catch(() => null)
    return { head, keys, checkpoint }
  }, [api])
  const [report, setReport] = useState<VerificationReport | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  async function run(action: () => Promise<unknown>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (reason) {
      setError(reason)
    } finally {
      setBusy(false)
    }
  }

  const data = state.data
  return (
    <>
      <Card title="Ledger" actions={<>
        <button type="button" disabled={busy}
          onClick={() => run(async () => { await api.createCheckpoint(); state.reload() })}>Sign a checkpoint</button>
        <button type="button" disabled={busy} onClick={() => run(async () => setReport(await api.verify()))}>
          Verify the whole ledger</button>
      </>}>
        <ErrorMessage error={state.error ?? error} />
        {data && (
          <dl className="summary">
            <dt>Head sequence</dt><dd>{data.head.sequence}</dd>
            <dt>Head hash</dt><dd><code>{data.head.hash}</code></dd>
            <dt>Latest checkpoint</dt>
            <dd>{data.checkpoint ? <>#{data.checkpoint.sequence}, <Time value={data.checkpoint.createdAt} />, key{' '}
              <code>{data.checkpoint.keyId}</code></> : 'none yet'}</dd>
            {data.checkpoint?.profiles && (
              <><dt>Compliance profiles</dt>
                <dd>{data.checkpoint.profiles.map((p) => p.id).join(', ')}, signed in the checkpoint</dd></>
            )}
          </dl>
        )}
        {report && (
          <div className={`report ${report.valid ? 'ok' : 'failed'}`} role="status">
            <strong>{report.valid ? 'The chain is valid.' : 'The chain is broken.'}</strong>{' '}
            {report.checkedEntries} entries and {report.checkedCheckpoints} checkpoints checked
            {report.failure && <>; sequence {report.failedSequence}: {report.failure}</>}
          </div>
        )}
      </Card>
      <Card title="Signing keys">
        {data && (
          <table>
            <thead><tr><th>Key ID</th><th>Status</th><th>Activated</th><th>Retired</th><th>Previous key</th></tr></thead>
            <tbody>
              {data.keys.map((k) => (
                <tr key={k.keyId}>
                  <td><code>{k.keyId}</code></td>
                  <td><Badge value={k.status} /></td>
                  <td><Time value={k.activatedAt} /></td>
                  <td><Time value={k.retiredAt} /></td>
                  <td><Hash value={k.rotation?.previousKeyId} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </>
  )
}
