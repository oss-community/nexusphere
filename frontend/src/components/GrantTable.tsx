import type { ReactNode } from 'react'
import type { Grant } from '../api/types'
import { Badge, Time } from './ui'

interface Props {
  items: Grant[]
  showPrincipal?: boolean
  actions: (grant: Grant) => ReactNode
  expanded?: (grant: Grant) => ReactNode
}

export function GrantTable({ items, showPrincipal = true, actions, expanded }: Props) {
  if (items.length === 0) {
    return <p className="muted">No grants.</p>
  }
  return (
    <table>
      <thead>
        <tr>
          {showPrincipal && <th>Principal</th>}
          <th>Agent</th>
          <th>Actions</th>
          <th>Targets</th>
          <th>Expires</th>
          <th>Uses</th>
          <th>Status</th>
          <th>Consent</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {items.map((g) => (
          <GrantRow key={g.id} grant={g} showPrincipal={showPrincipal} actions={actions(g)}
            expanded={expanded?.(g)} />
        ))}
      </tbody>
    </table>
  )
}

function GrantRow({ grant: g, showPrincipal, actions, expanded }:
  { grant: Grant; showPrincipal: boolean; actions: ReactNode; expanded?: ReactNode }) {
  return (
    <>
      <tr>
        {showPrincipal && <td>{g.principalId}</td>}
        <td>{g.agentId}</td>
        <td>{g.actions.join(', ')}</td>
        <td>{g.targets.join(', ')}</td>
        <td><Time value={g.expiresAt} /></td>
        <td>{g.maxUses ? `${g.uses} / ${g.maxUses}` : g.uses}</td>
        <td><Badge value={g.status} /></td>
        <td>{g.consent ?? '-'}</td>
        <td className="row-actions">{actions}</td>
      </tr>
      {expanded && (
        <tr className="detail"><td colSpan={showPrincipal ? 9 : 8}>{expanded}</td></tr>
      )}
    </>
  )
}
