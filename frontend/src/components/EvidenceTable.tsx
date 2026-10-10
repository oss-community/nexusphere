import { Fragment, useState } from 'react'
import type { Evidence } from '../api/types'
import { Badge, Hash, Time } from './ui'

export function EvidenceTable({ items }: { items: Evidence[] }) {
  const [open, setOpen] = useState<string | null>(null)
  if (items.length === 0) {
    return <p className="muted">No evidence yet.</p>
  }
  return (
    <table>
      <thead>
        <tr>
          <th>#</th>
          <th>Recorded</th>
          <th>Agent</th>
          <th>Principal</th>
          <th>Action</th>
          <th>Target</th>
          <th>Decision</th>
          <th>Outcome</th>
          <th>Hash</th>
        </tr>
      </thead>
      <tbody>
        {items.map((e) => (
          <Fragment key={e.id}>
            <tr className="clickable" onClick={() => setOpen(open === e.id ? null : e.id)}>
              <td>{e.sequence}</td>
              <td><Time value={e.recordedAt} /></td>
              <td>{e.agentId}</td>
              <td>{e.erased ? <em>erased</em> : (e.principalId ?? '-')}</td>
              <td>{e.action}</td>
              <td>{e.erased ? <em>erased</em> : (e.target ?? '-')}</td>
              <td><Badge value={e.decision} /></td>
              <td><Badge value={e.outcome} /></td>
              <td><Hash value={e.hash} /></td>
            </tr>
            {open === e.id && (
              <tr className="detail">
                <td colSpan={9}>
                  <dl>
                    <dt>ID</dt><dd><code>{e.id}</code></dd>
                    <dt>Occurred</dt><dd><Time value={e.occurredAt} /></dd>
                    <dt>Reason</dt><dd>{e.reason ?? '-'}</dd>
                    <dt>Delegation</dt><dd><code>{e.delegationId ?? '-'}</code></dd>
                    <dt>Correlation</dt><dd><code>{e.correlationId ?? '-'}</code></dd>
                    <dt>Input hash</dt><dd><code>{e.inputHash ?? '-'}</code></dd>
                    <dt>Output hash</dt><dd><code>{e.outputHash ?? '-'}</code></dd>
                    <dt>Previous hash</dt><dd><code>{e.previousHash}</code></dd>
                    <dt>Hash</dt><dd><code>{e.hash}</code></dd>
                    {Object.entries(e.attributes ?? {}).map(([key, value]) => (
                      <Fragment key={key}><dt>{key}</dt><dd><code>{value}</code></dd></Fragment>
                    ))}
                    {e.erased && (
                      <>
                        <dt>Erased</dt><dd>The personal fields were erased; their commitments keep the hash.</dd>
                        {Object.entries(e.commitments ?? {}).map(([key, value]) => (
                          <Fragment key={key}><dt>{key}</dt><dd><code>{value}</code></dd></Fragment>
                        ))}
                      </>
                    )}
                  </dl>
                </td>
              </tr>
            )}
          </Fragment>
        ))}
      </tbody>
    </table>
  )
}
