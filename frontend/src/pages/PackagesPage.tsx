import { useEffect, useState, type ChangeEvent, type FormEvent } from 'react'
import type { PackageReport } from '../api/types'
import { useSession } from '../auth/session'
import { Card, ErrorMessage } from '../components/ui'

function Report({ report }: { report: PackageReport }) {
  return (
    <div className={`report ${report.valid ? 'ok' : 'failed'}`} role="status">
      <strong>{report.valid ? 'Result: VALID' : 'Result: INVALID'}</strong>
      <dl className="summary">
        <dt>Signing key</dt><dd><code>{report.keyId ?? '-'}</code>{report.pinnedKeyId && ' (pinned)'}</dd>
        <dt>Checkpoint</dt><dd>sequence {report.checkpointSequence}</dd>
        {report.complianceProfiles?.length > 0 && (
          <><dt>Compliance</dt><dd>{report.complianceProfiles.map((p) => p.id).join(', ')}</dd></>
        )}
        <dt>Anchor</dt><dd>{report.anchorSequence === null ? 'genesis' : `checkpoint ${report.anchorSequence}`}</dd>
        <dt>Chain</dt><dd>{report.checkedLinks} links from sequence {report.firstSequence}</dd>
        <dt>Disclosed</dt><dd>{report.disclosedEntries} entries</dd>
      </dl>
      {report.problems.length > 0 && <ul>{report.problems.map((p) => <li key={p}>{p}</li>)}</ul>}
    </div>
  )
}

export function PackagesPage() {
  const { api } = useSession()
  const [agentId, setAgentId] = useState('')
  const [principalId, setPrincipalId] = useState('')
  const [publicKey, setPublicKey] = useState('')
  const [download, setDownload] = useState<{ url: string; name: string } | null>(null)
  const [pkg, setPkg] = useState<unknown>(null)
  const [report, setReport] = useState<PackageReport | null>(null)
  const [error, setError] = useState<unknown>(null)

  useEffect(() => {
    api.keys().then((keys) => setPublicKey(keys.find((k) => k.status === 'ACTIVE')?.publicKey ?? ''))
      .catch(setError)
  }, [api])

  useEffect(() => () => {
    if (download) {
      URL.revokeObjectURL(download.url)
    }
  }, [download])

  async function exportPackage(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setReport(null)
    try {
      const exported = await api.exportPackage({ agentId: agentId || undefined, principalId: principalId || undefined })
      const sequence = (exported as { checkpoint?: { sequence?: number } }).checkpoint?.sequence ?? 'latest'
      setPkg(exported)
      setDownload({
        url: URL.createObjectURL(new Blob([JSON.stringify(exported, null, 2)], { type: 'application/json' })),
        name: `evidence-package-${sequence}.json`,
      })
    } catch (reason) {
      setError(reason)
    }
  }

  async function upload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) {
      return
    }
    setError(null)
    setReport(null)
    setDownload(null)
    try {
      setPkg(JSON.parse(await file.text()))
    } catch {
      setError(new Error(`${file.name} is not JSON.`))
    }
  }

  async function verify() {
    setError(null)
    try {
      setReport(await api.verifyPackage(pkg, publicKey || undefined))
    } catch (reason) {
      setError(reason)
    }
  }

  return (
    <>
      <Card title="Export an evidence package">
        <form className="filters" onSubmit={exportPackage}>
          <label>Agent<input value={agentId} onChange={(e) => setAgentId(e.target.value)} /></label>
          <label>Principal<input value={principalId} onChange={(e) => setPrincipalId(e.target.value)} /></label>
          <button type="submit">Export</button>
        </form>
        {download && <p><a href={download.url} download={download.name}>Download {download.name}</a></p>}
      </Card>
      <Card title="Verify a package">
        <div className="stack">
          <label>Package file<input type="file" accept="application/json,.json" onChange={upload} /></label>
          <label>Pinned public key<input value={publicKey} onChange={(e) => setPublicKey(e.target.value)} /></label>
          <p className="muted">The ledger checks the package with the same library as the offline verifier. For a
            check that does not trust this ledger, run the verifier jar with the key you pinned earlier.</p>
          <div><button type="button" onClick={verify} disabled={pkg === null}>Verify</button></div>
        </div>
        {report && <Report report={report} />}
      </Card>
      <ErrorMessage error={error} />
    </>
  )
}
