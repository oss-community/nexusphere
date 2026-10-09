import { useState } from 'react'
import type { ChangeEvent, DragEvent, FormEvent } from 'react'
import { verify } from './verify'
import type { Verification } from './verify'

export function App() {
  const [fileName, setFileName] = useState<string | null>(null)
  const [packageText, setPackageText] = useState('')
  const [publicKey, setPublicKey] = useState('')
  const [witnesses, setWitnesses] = useState('')
  const [requiredWitnesses, setRequiredWitnesses] = useState('')
  const [result, setResult] = useState<Verification | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function load(file: File | undefined) {
    if (!file) {
      return
    }
    setFileName(file.name)
    setPackageText(await file.text())
    setResult(null)
    setError(null)
  }

  function chosen(event: ChangeEvent<HTMLInputElement>) {
    void load(event.target.files?.[0])
  }

  function dropped(event: DragEvent<HTMLLabelElement>) {
    event.preventDefault()
    void load(event.dataTransfer.files[0])
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    setResult(null)
    try {
      setResult(await verify({ packageText, publicKey, witnesses, requiredWitnesses }))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main>
      <header>
        <h1>Package Verifier</h1>
        <p className="muted">
          Checks a Nexusphere Ledger evidence package in this browser. The package and the keys never leave this page.
        </p>
      </header>
      <form onSubmit={submit} className="panel">
        <label
          className="drop"
          onDragOver={(event) => event.preventDefault()}
          onDrop={dropped}
        >
          <span>{fileName ?? 'Choose or drop the package file (JSON)'}</span>
          <input type="file" accept="application/json,.json" aria-label="Package file" onChange={chosen} />
        </label>
        <label>
          Ledger public key
          <textarea
            rows={2}
            value={publicKey}
            onChange={(event) => setPublicKey(event.target.value)}
            placeholder="MCowBQYDK2VwAyEA..., the publicKey that GET /api/v1/keys lists"
          />
        </label>
        <label>
          Witness keys, one per line
          <textarea
            rows={2}
            value={witnesses}
            onChange={(event) => setWitnesses(event.target.value)}
            placeholder="witness.example+1a2b3c4d+AbCd..."
          />
        </label>
        <label className="short">
          Required witnesses
          <input
            inputMode="numeric"
            value={requiredWitnesses}
            onChange={(event) => setRequiredWitnesses(event.target.value)}
            placeholder="all"
          />
        </label>
        <button type="submit" disabled={busy || packageText === ''}>
          {busy ? 'Verifying…' : 'Verify'}
        </button>
      </form>
      {error && (
        <p role="alert" className="badge bad">
          {error}
        </p>
      )}
      {result && <Result verification={result} />}
    </main>
  )
}

function Result({ verification }: { verification: Verification }) {
  const { report, pinned, entries } = verification
  const rows: [string, string][] = [
    ['Signing key', report.keyId ?? 'none'],
    ['Pinned key', report.pinnedKeyId ?? 'not pinned'],
    ['Checkpoint', `${report.checkpointSequence} at ${report.checkpointCreatedAt ?? 'unknown time'}`],
    ['Anchor', report.anchorSequence === null ? 'none, the chain starts at entry 1' : String(report.anchorSequence)],
    ['Checked links', `${report.checkedLinks} from sequence ${report.firstSequence}`],
    ['Disclosed entries', String(report.disclosedEntries)],
    ['Scope', `agent ${report.agentId ?? 'any'}, principal ${report.principalId ?? 'any'}`],
    ['Transparency log', report.logOrigin ? `${report.logOrigin}, tree size ${report.logTreeSize}` : 'none'],
    ['Inclusion proofs', String(report.provenEntries)],
    ['Receipts', String(report.receiptedEntries)],
    ['Witnesses', report.witnesses.length ? report.witnesses.join(', ') : 'none'],
  ]
  return (
    <section className="panel" aria-label="Result">
      <p className={`badge ${report.valid ? 'good' : 'bad'}`} data-testid="verdict">
        {report.valid ? 'VALID' : 'INVALID'}
      </p>
      {report.valid && !pinned && (
        <p className="badge wait" role="note">
          Not pinned: the package was checked only against the keys it carries. Anyone can build such a package; paste
          the ledger's public key to know who signed it.
        </p>
      )}
      {report.problems.length > 0 && (
        <ul className="problems">
          {report.problems.map((problem) => (
            <li key={problem}>{problem}</li>
          ))}
        </ul>
      )}
      <dl>
        {rows.map(([name, value]) => (
          <div key={name}>
            <dt>{name}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      {entries.length > 0 && (
        <div className="scroll">
          <table>
            <thead>
              <tr>
                <th>Sequence</th>
                <th>Occurred</th>
                <th>Agent</th>
                <th>Principal</th>
                <th>Action</th>
                <th>Target</th>
                <th>Decision</th>
                <th>Outcome</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry) => (
                <tr key={entry.sequence}>
                  <td>{entry.sequence}</td>
                  <td>{entry.occurredAt}</td>
                  <td>{entry.agentId}</td>
                  <td>{entry.principalId}</td>
                  <td>{entry.action}</td>
                  <td>{entry.target ?? ''}</td>
                  <td>{entry.decision ?? ''}</td>
                  <td>{entry.outcome}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
