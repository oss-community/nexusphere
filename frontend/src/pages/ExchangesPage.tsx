import { useState, type FormEvent } from 'react'
import type { Exchange } from '../api/types'
import { useSession } from '../auth/session'
import { Badge, Card, ErrorMessage, Time } from '../components/ui'

export function ExchangesPage() {
  const { api } = useSession()
  const [id, setId] = useState('')
  const [exchange, setExchange] = useState<Exchange | null>(null)
  const [error, setError] = useState<unknown>(null)

  async function find(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setExchange(null)
    try {
      setExchange(await api.exchange(id.trim()))
    } catch (reason) {
      setError(reason)
    }
  }

  return (
    <Card title="A2A exchanges">
      <form className="filters" onSubmit={find}>
        <label>Exchange ID<input value={id} onChange={(e) => setId(e.target.value)} required /></label>
        <button type="submit">Find</button>
      </form>
      <ErrorMessage error={error} />
      {exchange && (
        <dl className="summary">
          <dt>Direction</dt><dd>{exchange.direction} {exchange.direction === 'OUTBOUND' ? 'to' : 'from'} {exchange.peer}</dd>
          <dt>Agent</dt><dd>{exchange.agentId}</dd>
          <dt>Principal</dt><dd>{exchange.principalId}</dd>
          <dt>Method</dt><dd>{exchange.method}</dd>
          <dt>Created</dt><dd><Time value={exchange.createdAt} /></dd>
          <dt>Status</dt><dd>{exchange.status ?? '-'}</dd>
          <dt>Outcome</dt><dd><Badge value={exchange.outcome} /></dd>
          <dt>Receipt</dt><dd><Badge value={exchange.receiptStatus} /></dd>
          <dt>Request hash</dt><dd><code>{exchange.requestHash}</code></dd>
          <dt>Response hash</dt><dd><code>{exchange.responseHash ?? '-'}</code></dd>
          <dt>Mandate</dt><dd><code className="token">{exchange.mandate}</code></dd>
          <dt>Request proof</dt><dd><code className="token">{exchange.request}</code></dd>
          <dt>Receipt token</dt><dd><code className="token">{exchange.receipt ?? '-'}</code></dd>
        </dl>
      )}
    </Card>
  )
}
