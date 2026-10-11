import type { ReactNode } from 'react'
import type { Approval } from '../api/types'
import { Badge, Time } from './ui'

interface Props {
  items: Approval[]
  showPrincipal?: boolean
  actions: (approval: Approval) => ReactNode
}

export function ApprovalTable({ items, showPrincipal = true, actions }: Props) {
  if (items.length === 0) {
    return <p className="muted">No approvals.</p>
  }
  return (
    <table>
      <thead>
        <tr>
          <th>Agent</th>
          {showPrincipal && <th>Principal</th>}
          <th>Action</th>
          <th>Target</th>
          <th>Policies</th>
          <th>Approver</th>
          <th>Status</th>
          <th>Expires</th>
          <th>Method</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {items.map((a) => (
          <tr key={a.id}>
            <td>{a.agentId}</td>
            {showPrincipal && <td>{a.principalId}</td>}
            <td>{a.action}</td>
            <td>{a.target ?? '-'}</td>
            <td>{a.policies.join(', ')}</td>
            <td>{a.approver}</td>
            <td><Badge value={a.status} />{a.used && <span className="muted"> used</span>}</td>
            <td><Time value={a.expiresAt} /></td>
            <td title={a.reason ?? undefined}>{a.method ?? '-'}</td>
            <td className="row-actions">{actions(a)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
