import { useState, type FormEvent } from 'react'
import type { GrantDraft } from '../api/types'
import { ErrorMessage, inOneHour, splitList } from './ui'

interface Props {
  askPrincipal: boolean
  submitLabel: string
  onSubmit: (draft: GrantDraft) => Promise<void>
}

export function GrantForm({ askPrincipal, submitLabel, onSubmit }: Props) {
  const [principalId, setPrincipalId] = useState('')
  const [agentId, setAgentId] = useState('')
  const [actions, setActions] = useState('tools/call')
  const [targets, setTargets] = useState('')
  const [expiresAt, setExpiresAt] = useState(inOneHour())
  const [maxUses, setMaxUses] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await onSubmit({
        principalId: askPrincipal ? principalId : undefined,
        agentId,
        actions: splitList(actions),
        targets: splitList(targets),
        expiresAt: new Date(expiresAt).toISOString(),
        maxUses: maxUses ? Number(maxUses) : undefined,
        reason: reason || undefined,
      })
      setTargets('')
      setReason('')
    } catch (reason) {
      setError(reason)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="form" onSubmit={submit}>
      {askPrincipal && (
        <label>Principal<input value={principalId} onChange={(e) => setPrincipalId(e.target.value)} required /></label>
      )}
      <label>Agent<input value={agentId} onChange={(e) => setAgentId(e.target.value)} required /></label>
      <label>Actions<input value={actions} onChange={(e) => setActions(e.target.value)} required /></label>
      <label>Targets<input value={targets} onChange={(e) => setTargets(e.target.value)} placeholder="demo/read_file, demo/*" required /></label>
      <label>Expires<input type="datetime-local" value={expiresAt} onChange={(e) => setExpiresAt(e.target.value)} required /></label>
      <label>Max uses<input type="number" min="1" value={maxUses} onChange={(e) => setMaxUses(e.target.value)} /></label>
      <label className="wide">Reason<input value={reason} onChange={(e) => setReason(e.target.value)} /></label>
      <div className="wide">
        <button type="submit" disabled={busy}>{submitLabel}</button>
      </div>
      <ErrorMessage error={error} />
    </form>
  )
}
