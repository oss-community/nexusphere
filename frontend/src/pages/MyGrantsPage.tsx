import { useState } from 'react'
import { useSession } from '../auth/session'
import { GrantForm } from '../components/GrantForm'
import { GrantTable } from '../components/GrantTable'
import { Card, ErrorMessage, Time, useLoad } from '../components/ui'

export function MyGrantsPage() {
  const { api, session } = useSession()
  const grants = useLoad(() => api.myGrants(), [api])
  const [error, setError] = useState<unknown>(null)

  async function act(action: () => Promise<unknown>) {
    setError(null)
    try {
      await action()
      grants.reload()
    } catch (reason) {
      setError(reason)
    }
  }

  const principal = session?.kind === 'principal' ? session.principal : null
  const items = grants.data?.items ?? []
  const pending = items.filter((g) => g.status === 'PENDING')
  const others = items.filter((g) => g.status !== 'PENDING')

  return (
    <>
      {principal && (
        <p className="muted">Signed in as <strong>{principal.name}</strong> ({principal.principalId}) at{' '}
          {principal.issuer}{principal.authTime && <>, since <Time value={principal.authTime} /></>}</p>
      )}
      <ErrorMessage error={grants.error ?? error} />
      <Card title={`Waiting for you (${pending.length})`}>
        <GrantTable items={pending} showPrincipal={false} actions={(g) => <>
          <button type="button" onClick={() => act(() => api.approve(g.id))}>Approve</button>
          <button type="button" className="danger" onClick={() => act(() => api.deny(g.id))}>Deny</button>
        </>} />
      </Card>
      <Card title="My grants">
        <GrantTable items={others} showPrincipal={false} actions={(g) => (
          g.status === 'ACTIVE' || g.status === 'NOT_YET_VALID'
            ? <button type="button" className="danger" onClick={() => act(() => api.revokeMine(g.id))}>Revoke</button>
            : null
        )} />
      </Card>
      <Card title="Grant an agent">
        <GrantForm askPrincipal={false} submitLabel="Grant" onSubmit={async (draft) => {
          await api.createMyGrant(draft)
          grants.reload()
        }} />
      </Card>
    </>
  )
}
