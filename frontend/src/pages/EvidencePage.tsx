import { useCallback, useEffect, useState, type FormEvent } from 'react'
import type { Evidence } from '../api/types'
import { useSession } from '../auth/session'
import { EvidenceTable } from '../components/EvidenceTable'
import { Card, ErrorMessage } from '../components/ui'

export function EvidencePage({ mine = false }: { mine?: boolean }) {
  const { api } = useSession()
  const [agentId, setAgentId] = useState('')
  const [principalId, setPrincipalId] = useState('')
  const [items, setItems] = useState<Evidence[] | null>(null)
  const [next, setNext] = useState<number | null>(null)
  const [error, setError] = useState<unknown>(null)

  const load = useCallback(async (filter: { agentId: string; principalId: string }, after?: number) => {
    setError(null)
    try {
      const page = mine ? await api.myEvidence(after) : await api.evidence({ ...filter, after, limit: 100 })
      setItems((current) => (after && current ? [...current, ...page.items] : page.items))
      setNext(typeof page.nextAfter === 'number' ? page.nextAfter : null)
    } catch (reason) {
      setError(reason)
    }
  }, [api, mine])

  useEffect(() => {
    load({ agentId: '', principalId: '' })
  }, [load])

  function search(event: FormEvent) {
    event.preventDefault()
    load({ agentId, principalId })
  }

  return (
    <Card title={mine ? 'Evidence about me' : 'Evidence'}>
      {!mine && (
        <form className="filters" onSubmit={search}>
          <label>Agent<input value={agentId} onChange={(e) => setAgentId(e.target.value)} /></label>
          <label>Principal<input value={principalId} onChange={(e) => setPrincipalId(e.target.value)} /></label>
          <button type="submit">Search</button>
        </form>
      )}
      <ErrorMessage error={error} />
      {items && <EvidenceTable items={items} />}
      {next !== null && <button type="button" className="secondary" onClick={() => load({ agentId, principalId }, next)}>Load more</button>}
    </Card>
  )
}
