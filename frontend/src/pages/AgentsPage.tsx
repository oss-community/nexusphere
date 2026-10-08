import { useState, type FormEvent } from 'react'
import type { IssuedAgent } from '../api/types'
import { useSession } from '../auth/session'
import { Badge, Card, ErrorMessage, Time, useLoad } from '../components/ui'

export function AgentsPage() {
  const { api } = useSession()
  const agents = useLoad(() => api.agents(), [api])
  const [agentId, setAgentId] = useState('')
  const [name, setName] = useState('')
  const [ownerId, setOwnerId] = useState('')
  const [issued, setIssued] = useState<IssuedAgent | null>(null)
  const [error, setError] = useState<unknown>(null)

  async function register(event: FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      setIssued(await api.registerAgent({ agentId, name, ownerId }))
      setAgentId('')
      setName('')
      agents.reload()
    } catch (reason) {
      setError(reason)
    }
  }

  async function disable(id: string) {
    setError(null)
    try {
      await api.disableAgent(id)
      agents.reload()
    } catch (reason) {
      setError(reason)
    }
  }

  return (
    <>
      <Card title="Register an agent">
        <form className="form" onSubmit={register}>
          <label>Agent ID<input value={agentId} onChange={(e) => setAgentId(e.target.value)} required /></label>
          <label>Name<input value={name} onChange={(e) => setName(e.target.value)} required /></label>
          <label>Owner<input value={ownerId} onChange={(e) => setOwnerId(e.target.value)} required /></label>
          <div className="wide"><button type="submit">Register</button></div>
        </form>
        {issued && (
          <div className="report ok" role="status">
            API key for <strong>{issued.agentId}</strong>, shown only now: <code>{issued.apiKey}</code>
          </div>
        )}
        <ErrorMessage error={error} />
      </Card>
      <Card title="Agents">
        <ErrorMessage error={agents.error} />
        {agents.data && (
          <table>
            <thead><tr><th>Agent ID</th><th>Name</th><th>Owner</th><th>Key</th><th>Status</th><th>Created</th><th /></tr></thead>
            <tbody>
              {agents.data.items.map((a) => (
                <tr key={a.agentId}>
                  <td>{a.agentId}</td>
                  <td>{a.name}</td>
                  <td>{a.ownerId}</td>
                  <td><code>{a.keyPrefix}…</code></td>
                  <td><Badge value={a.status} /></td>
                  <td><Time value={a.createdAt} /></td>
                  <td className="row-actions">
                    {a.status === 'ACTIVE' && (
                      <button type="button" className="danger" onClick={() => disable(a.agentId)}>Disable</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </>
  )
}
