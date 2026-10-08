import { useState } from 'react'
import type { Grant } from '../api/types'
import { useSession } from '../auth/session'
import { GrantForm } from '../components/GrantForm'
import { GrantTable } from '../components/GrantTable'
import { Badge, Card, ErrorMessage, Time, useLoad } from '../components/ui'

function Mandates({ grant }: { grant: Grant }) {
  const { api } = useSession()
  const mandates = useLoad(() => api.mandates(grant.id), [api, grant.id])
  const [error, setError] = useState<unknown>(null)

  async function revoke(id: string) {
    try {
      await api.revokeMandate(id)
      mandates.reload()
    } catch (reason) {
      setError(reason)
    }
  }

  return (
    <div>
      <p className="muted">Grant <code>{grant.id}</code>, terms hash <code>{grant.termsHash}</code>
        {grant.reason && <>, reason: {grant.reason}</>}</p>
      <ErrorMessage error={mandates.error ?? error} />
      {mandates.data && mandates.data.length === 0 && <p className="muted">No mandates issued.</p>}
      {mandates.data && mandates.data.length > 0 && (
        <table>
          <thead><tr><th>Mandate</th><th>Audience</th><th>Issued</th><th>Expires</th><th>Status</th><th /></tr></thead>
          <tbody>
            {mandates.data.map((m) => (
              <tr key={m.id}>
                <td><code>{m.id}</code></td>
                <td>{m.audience}</td>
                <td><Time value={m.issuedAt} /></td>
                <td><Time value={m.expiresAt} /></td>
                <td><Badge value={m.status} /></td>
                <td className="row-actions">
                  {m.status === 'ACTIVE' && (
                    <button type="button" className="danger" onClick={() => revoke(m.id)}>Revoke</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

export function GrantsPage() {
  const { api } = useSession()
  const grants = useLoad(() => api.grants({}), [api])
  const [open, setOpen] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)

  async function revoke(id: string) {
    setError(null)
    try {
      await api.revokeGrant(id)
      grants.reload()
    } catch (reason) {
      setError(reason)
    }
  }

  return (
    <>
      <Card title="Create a grant">
        <p className="muted">When the ledger has an OpenID Connect provider, a new grant waits for the principal to
          approve it.</p>
        <GrantForm askPrincipal submitLabel="Create" onSubmit={async (draft) => {
          await api.createGrant(draft)
          grants.reload()
        }} />
      </Card>
      <Card title="Grants">
        <ErrorMessage error={grants.error ?? error} />
        {grants.data && (
          <GrantTable items={grants.data.items}
            actions={(g) => <>
              <button type="button" className="secondary" onClick={() => setOpen(open === g.id ? null : g.id)}>
                {open === g.id ? 'Hide' : 'Details'}</button>
              {(g.status === 'ACTIVE' || g.status === 'PENDING' || g.status === 'NOT_YET_VALID') && (
                <button type="button" className="danger" onClick={() => revoke(g.id)}>Revoke</button>
              )}
            </>}
            expanded={(g) => (open === g.id ? <Mandates grant={g} /> : null)} />
        )}
      </Card>
    </>
  )
}
