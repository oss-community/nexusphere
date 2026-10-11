import { useState } from 'react'
import { useSession } from '../auth/session'
import { ApprovalTable } from '../components/ApprovalTable'
import { Card, ErrorMessage, useLoad } from '../components/ui'

const STATUSES = ['PENDING', 'APPROVED', 'DENIED', 'EXPIRED']

export function ApprovalsPage() {
  const { api } = useSession()
  const [status, setStatus] = useState('PENDING')
  const approvals = useLoad(() => api.approvals({ status: status || undefined }), [api, status])
  const [error, setError] = useState<unknown>(null)

  async function answer(question: string, action: (reason?: string) => Promise<unknown>) {
    const reason = window.prompt(question)
    if (reason === null) {
      return
    }
    setError(null)
    try {
      await action(reason || undefined)
      approvals.reload()
    } catch (failure) {
      setError(failure)
    }
  }

  return (
    <Card title="Approvals" actions={
      <label>Status{' '}
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
          <option value="">All</option>
        </select>
      </label>
    }>
      <p className="muted">A policy annotated with <code>@approval</code> holds the agent's action until it is
        approved; the agent then repeats the identical request once.</p>
      <ErrorMessage error={approvals.error ?? error} />
      {approvals.data && (
        <ApprovalTable items={approvals.data.items} actions={(a) => (
          a.status === 'PENDING' && a.approver !== 'principal'
        ) && <>
          <button type="button" onClick={() => answer('Reason for approving (optional)',
            (reason) => api.approveApproval(a.id, reason))}>Approve</button>
          <button type="button" className="danger" onClick={() => answer('Reason for denying (optional)',
            (reason) => api.denyApproval(a.id, reason))}>Deny</button>
        </>} />
      )}
    </Card>
  )
}
