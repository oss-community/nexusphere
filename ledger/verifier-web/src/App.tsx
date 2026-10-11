import { useState } from 'react'
import type { ChangeEvent, DragEvent, FormEvent } from 'react'
import { verify } from './verify'
import type { IndexVerification, TrackRecordVerification, Verification } from './verify'
import { instantText } from './trackrecord'

export function App() {
  const [fileName, setFileName] = useState<string | null>(null)
  const [packageText, setPackageText] = useState('')
  const [pasted, setPasted] = useState('')
  const [publicKey, setPublicKey] = useState('')
  const [keyListName, setKeyListName] = useState<string | null>(null)
  const [keyListText, setKeyListText] = useState('')
  const [witnesses, setWitnesses] = useState('')
  const [requiredWitnesses, setRequiredWitnesses] = useState('')
  const [chainName, setChainName] = useState<string | null>(null)
  const [chainText, setChainText] = useState('')
  const [requireTimestamp, setRequireTimestamp] = useState(false)
  const [rekorKeys, setRekorKeys] = useState('')
  const [requireRekor, setRequireRekor] = useState(false)
  const [indexProofName, setIndexProofName] = useState<string | null>(null)
  const [indexProofText, setIndexProofText] = useState('')
  const [result, setResult] = useState<Verification | IndexVerification | TrackRecordVerification | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function load(file: File | undefined) {
    if (!file) {
      return
    }
    setFileName(file.name)
    setPackageText(await file.text())
    setPasted('')
    setResult(null)
    setError(null)
  }

  async function loadKeyList(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) {
      return
    }
    setKeyListName(file.name)
    setKeyListText(await file.text())
    setResult(null)
  }

  async function loadChain(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) {
      return
    }
    setChainName(file.name)
    setChainText(await file.text())
    setResult(null)
  }

  async function loadIndexProof(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) {
      return
    }
    setIndexProofName(file.name)
    setIndexProofText(await file.text())
    setResult(null)
  }

  function paste(text: string) {
    setPasted(text)
    setResult(null)
    setError(null)
  }

  const input = pasted.trim() !== '' ? pasted : packageText

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
      setResult(await verify({
        packageText: input,
        publicKey,
        keyListText,
        witnesses,
        requiredWitnesses,
        timestampCertificatesText: chainText,
        requireTimestamp,
        rekorKeys,
        requireRekor,
        indexProofText,
      }))
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
          Checks a Nexusphere Ledger evidence package, index proof or agent track record in this browser. The file and
          the keys never leave this page.
        </p>
      </header>
      <form onSubmit={submit} className="panel">
        <label
          className="drop"
          onDragOver={(event) => event.preventDefault()}
          onDrop={dropped}
        >
          <span>{fileName ?? 'Choose or drop the package or index proof file (JSON), or a track record'}</span>
          <input
            type="file"
            accept="application/json,.json,text/plain,.txt,.jwt,.sd-jwt"
            aria-label="Package file"
            onChange={chosen}
          />
        </label>
        <label>
          Or paste a track record
          <textarea
            rows={2}
            value={pasted}
            onChange={(event) => paste(event.target.value)}
            placeholder="eyJhbGciOiJFZERTQSIsInR5cCI6ImRjK3NkLWp3dCIs...~, the SD-JWT VC of an agent track record"
          />
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
        <label className="drop small">
          <span>{keyListName ?? 'Optional: the ledger key list (JSON from GET /api/v1/keys), to apply revocations'}</span>
          <input
            type="file"
            accept="application/json,.json"
            aria-label="Key list file"
            onChange={(event) => void loadKeyList(event)}
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
        <label className="drop small">
          <span>{chainName ?? 'Optional: the timestamp authority chain (PEM), to pin who timestamped the checkpoint'}</span>
          <input
            type="file"
            accept=".pem,.crt,application/x-pem-file"
            aria-label="Timestamp authority chain file"
            onChange={(event) => void loadChain(event)}
          />
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={requireTimestamp}
            onChange={(event) => setRequireTimestamp(event.target.checked)}
          />
          Require a timestamp
        </label>
        <label>
          Rekor log keys, one per line
          <textarea
            rows={2}
            value={rekorKeys}
            onChange={(event) => setRekorKeys(event.target.value)}
            placeholder="rekor.example+1a2b3c4d+AbCd..., the key that GET /public/v1/rekor lists"
          />
        </label>
        <label className="check">
          <input type="checkbox" checked={requireRekor} onChange={(event) => setRequireRekor(event.target.checked)} />
          Require a Rekor entry
        </label>
        <label className="drop small">
          <span>{indexProofName ?? 'Optional: an index proof of the agent (JSON), to cross-check a track record'}</span>
          <input
            type="file"
            accept="application/json,.json"
            aria-label="Index proof file"
            onChange={(event) => void loadIndexProof(event)}
          />
        </label>
        <button type="submit" disabled={busy || input.trim() === ''}>
          {busy ? 'Verifying…' : 'Verify'}
        </button>
      </form>
      {error && (
        <p role="alert" className="badge bad">
          {error}
        </p>
      )}
      {result && ('trackRecord' in result
        ? <TrackRecordResult verification={result} />
        : 'index' in result
          ? <IndexResult verification={result} />
          : <Result verification={result} />)}
    </main>
  )
}

function IndexResult({ verification }: { verification: IndexVerification }) {
  const { index, pinned } = verification
  const rows: [string, string][] = [
    ['Term', Object.values(index.term).join(' ') || 'unknown'],
    ['Checkpoint', `${index.sequence ?? 'unknown'}, head ${index.headHash ?? 'unknown'}`],
    ['Signed by', `${index.keyId ?? 'unknown'}${pinned ? '' : ', not pinned'}`],
    ['Finding', index.present
      ? `${index.count} entries, sequences ${index.firstSequence} to ${index.lastSequence}`
      : `no entry up to sequence ${index.sequence ?? 'unknown'}`],
  ]
  return (
    <section className="panel" aria-label="Result">
      <p className={`badge ${index.valid ? 'good' : 'bad'}`} data-testid="verdict">
        {index.valid ? 'VALID' : 'INVALID'}
      </p>
      {index.valid && !pinned && (
        <p className="badge wait" role="note">
          Not pinned: the index proof was checked only against the keys it carries. Anyone can build such a proof; paste
          the ledger's public key to know who signed it.
        </p>
      )}
      {index.problems.length > 0 && (
        <ul className="problems">
          {index.problems.map((problem) => (
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
    </section>
  )
}

function counts(values: Record<string, number> | null): string {
  if (values === null) {
    return 'withheld'
  }
  const entries = Object.entries(values)
  return entries.length ? entries.map(([name, count]) => `${name} ${count}`).join(', ') : 'none'
}

function TrackRecordResult({ verification }: { verification: TrackRecordVerification }) {
  const { trackRecord, pinned, index, mismatches, valid } = verification
  const claims = trackRecord.claims
  const problems = [
    ...trackRecord.problems,
    ...(index ? index.problems.map((problem) => `Index proof: ${problem}`) : []),
    ...mismatches,
  ]
  const rows: [string, string][] = claims
    ? [
        ['Credential', claims.id],
        ['Issuer', claims.issuer],
        ['Agent', claims.agentId],
        ['Audience', claims.audience ?? 'any'],
        ['Valid', `${instantText(claims.issuedAt)} to ${instantText(claims.expiresAt)}`],
        ['Checkpoint', `${claims.checkpointSequence}, head ${claims.headHash}`],
        ['Index root', claims.indexRoot],
        ['Entries', String(claims.total) + (claims.firstSequence === null
          ? ''
          : `, sequences ${claims.firstSequence} to ${claims.lastSequence}, ${claims.firstAt} to ${claims.lastAt}`)],
        ['Outcomes', counts(claims.outcomes)],
        ['Principals', claims.principals === null ? 'withheld' : String(claims.principals)],
        ['Actions', counts(claims.actions)],
        ['Agent key', claims.holderKey ? claims.holderKey.keyId : 'none'],
        ['Signed by', `${trackRecord.keyId ?? 'unknown'}${pinned ? '' : ', not pinned'}`],
      ]
    : []
  if (index) {
    rows.push([
      'Index proof',
      `${index.present ? index.count : 0} entries at checkpoint ${index.sequence ?? 'unknown'}, ` +
        (index.valid && mismatches.length === 0 ? 'matches the track record' : 'does not match the track record'),
    ])
  }
  return (
    <section className="panel" aria-label="Result">
      <p className={`badge ${valid ? 'good' : 'bad'}`} data-testid="verdict">
        {valid ? 'VALID' : 'INVALID'}
      </p>
      {valid && !pinned && (
        <p className="badge wait" role="note">
          Not pinned: the track record was checked only against the key list. Paste the ledger's public key to know who
          signed it.
        </p>
      )}
      {problems.length > 0 && (
        <ul className="problems">
          {problems.map((problem) => (
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
    </section>
  )
}

function Result({ verification }: { verification: Verification }) {
  const { report, pinned, timestampPinned, rekorPinned, entries } = verification
  const modelled = entries.some((entry) => entry.model !== null)
  const rows: [string, string][] = [
    ['Signing key', report.keyId ?? 'none'],
    ['Pinned key', report.pinnedKeyId ?? 'not pinned'],
    ['Checkpoint', `${report.checkpointSequence} at ${report.checkpointCreatedAt ?? 'unknown time'}`],
    ['Compliance profiles', report.complianceProfiles.length
      ? report.complianceProfiles.map((p) => `${p.id} (${p.digest.slice(0, 12)})`).join(', ') + ', signed in the checkpoint'
      : 'none signed'],
    ['Timestamp', report.timestampedAt
      ? `${report.timestampedAt} by ${report.timestampAuthority}` +
        (timestampPinned ? ' (pinned)' : ', authority not pinned')
      : 'none'],
    ['Rekor', report.rekorLogIndex != null
      ? `entry ${report.rekorLogIndex} in ${report.rekorOrigin}` + (rekorPinned ? ' (pinned)' : ', log not pinned')
      : 'none'],
    ['Anchor', report.anchorSequence === null ? 'none, the chain starts at entry 1' : String(report.anchorSequence)],
    ['Checked links', `${report.checkedLinks} from sequence ${report.firstSequence}`],
    ['Disclosed entries', String(report.disclosedEntries)],
    ...(report.disclosedPrompts > 0
      ? [['Prompts', `${report.disclosedPrompts} prompt texts match the hashes their entries name`] as [string, string]]
      : []),
    ...(report.disclosedPolicies > 0
      ? [['Policies', `${report.disclosedPolicies} policy set texts match the hashes their entries name`] as [string, string]]
      : []),
    ['Scope', `agent ${report.agentId ?? 'any'}, principal ${report.principalId ?? 'any'}`],
    ['Transparency log', report.logOrigin ? `${report.logOrigin}, tree size ${report.logTreeSize}` : 'none'],
    ['Inclusion proofs', String(report.provenEntries)],
    ['Receipts', String(report.receiptedEntries)],
    ['Witnesses', report.witnesses.length ? report.witnesses.join(', ') : 'none'],
  ]
  if (report.revokedKeys.length > 0) {
    rows.push([
      'Revoked keys',
      report.revokedKeys.join(', ') +
        (report.valid ? ', the witnesses prove the log checkpoint predates the compromise' : ''),
    ])
  }
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
                {modelled && <th>Model</th>}
              </tr>
            </thead>
            <tbody>
              {entries.map((entry) => (
                <tr key={entry.sequence}>
                  <td>{entry.sequence}</td>
                  <td>{entry.occurredAt}</td>
                  <td>{entry.agentId}</td>
                  <td>{entry.erased ? <em>erased</em> : entry.principalId}</td>
                  <td>{entry.action}</td>
                  <td>{entry.erased ? <em>erased</em> : (entry.target ?? '')}</td>
                  <td>{entry.decision ?? ''}</td>
                  <td>{entry.outcome}</td>
                  {modelled && <td>{entry.model ?? ''}</td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
