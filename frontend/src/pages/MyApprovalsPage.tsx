import { useState } from 'react'
import { useSession } from '../auth/session'
import { ApprovalTable } from '../components/ApprovalTable'
import { Card, ErrorMessage, useLoad } from '../components/ui'

export function MyApprovalsPage() {
  const { api } = useSession()
  const approvals = useLoad(() => api.myApprovals(), [api])
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

  const items = approvals.data?.items ?? []
  const pending = items.filter((a) => a.status === 'PENDING')
  const others = items.filter((a) => a.status !== 'PENDING')

  return (
    <>
      <ErrorMessage error={approvals.error ?? error} />
      <Card title={`Approvals waiting for you (${pending.length})`}>
        <ApprovalTable items={pending} showPrincipal={false} actions={(a) => a.approver !== 'operator' && <>
          <button type="button" onClick={() => answer('Reason for approving (optional)',
            (reason) => api.approveMyApproval(a.id, reason))}>Approve</button>
          <button type="button" className="danger" onClick={() => answer('Reason for denying (optional)',
            (reason) => api.denyMyApproval(a.id, reason))}>Deny</button>
        </>} />
      </Card>
      <Card title="My approvals">
        <ApprovalTable items={others} showPrincipal={false} actions={() => null} />
      </Card>
    </>
  )
}
