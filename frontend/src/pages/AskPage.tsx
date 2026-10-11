import { useEffect, useState, type FormEvent } from 'react'
import type { AskAnswer, AskCitation, AskPlan } from '../api/types'
import { useSession } from '../auth/session'
import { Badge, Card, ErrorMessage, Hash, Time } from '../components/ui'

export const OPERATOR_ASK = '/api/v1/ask'
export const PRINCIPAL_ASK = '/api/v1/principal/ask'

const MAX_LENGTH = 500

const EXAMPLES = [
  'What did my agents do this week?',
  'Which calls were denied today?',
  'Show failed calls in the last 7 days',
]

const PLAN_LABELS: [keyof AskPlan, string][] = [
  ['agentId', 'Agent'],
  ['principalId', 'Principal'],
  ['action', 'Action'],
  ['target', 'Target'],
  ['decision', 'Decision'],
  ['outcome', 'Outcome'],
  ['from', 'From'],
  ['to', 'To'],
]

const CHECKS: (keyof AskCitation['checks'])[] = ['content', 'link', 'inclusion']

function rowId(sequence: number) {
  return `cited-${sequence}`
}

function Checks({ citation }: { citation: AskCitation }) {
  const summary = CHECKS.map((check) => `${check} ${citation.checks[check] ? 'passed' : 'failed'}`).join(', ')
  if (CHECKS.every((check) => citation.checks[check])) {
    return <span className="badge good" title={summary}>Verified</span>
  }
  return (
    <span className="checks" title={summary}>
      {CHECKS.map((check) => (
        <span key={check} className={`badge ${citation.checks[check] ? 'good' : 'bad'}`}>
          {check} {citation.checks[check] ? 'passed' : 'failed'}
        </span>
      ))}
    </span>
  )
}

function Plan({ plan }: { plan: AskPlan | null }) {
  const fields = PLAN_LABELS.filter(([key]) => plan?.[key] != null)
  if (!plan || fields.length === 0) {
    return null
  }
  return (
    <ul className="chips" aria-label="Search plan">
      {fields.map(([key, label]) => (
        <li key={key} className="chip"><span className="muted">{label}</span> {plan[key]}</li>
      ))}
    </ul>
  )
}

function Answer({ result, onCite }: { result: AskAnswer; onCite: (sequence: number) => void }) {
  return (
    <div className="stack">
      <Plan plan={result.plan} />
      {result.sentences.length === 0
        ? <p>{result.answer}</p>
        : (
          <ol className="answer" aria-label="Answer">
            {result.sentences.map((sentence, index) => (
              <li key={index}>
                <span>{sentence.text}</span>
                {sentence.citations.map((sequence) => (
                  <button key={sequence} type="button" className="chip cite" onClick={() => onCite(sequence)}>
                    #{sequence}
                  </button>
                ))}
              </li>
            ))}
          </ol>
        )}
      {result.unverified.length > 0 && (
        <p className="warning" role="status">
          {result.unverified.length === 1 ? 'Entry' : 'Entries'} {result.unverified.map((s) => `#${s}`).join(', ')}{' '}
          failed verification and {result.unverified.length === 1 ? 'was' : 'were'} not used.
        </p>
      )}
      <p className="muted ask-footer">
        Model {result.model} · {result.considered} {result.considered === 1 ? 'entry' : 'entries'} considered ·{' '}
        {result.checkpointSize === null ? 'no checkpoint' : `checkpoint size ${result.checkpointSize}`} · Recorded as
        #{result.recordedAs}
        {result.droppedSentences > 0 && (
          <> · {result.droppedSentences} {result.droppedSentences === 1 ? 'sentence' : 'sentences'} without valid
            citations {result.droppedSentences === 1 ? 'was' : 'were'} removed</>
        )}
      </p>
    </div>
  )
}

function CitedEvidence({ items, focused }: { items: AskCitation[]; focused: number | null }) {
  return (
    <table>
      <thead>
        <tr>
          <th>#</th>
          <th>Occurred</th>
          <th>Agent</th>
          <th>Principal</th>
          <th>Action</th>
          <th>Target</th>
          <th>Decision</th>
          <th>Outcome</th>
          <th>Hash</th>
          <th>Checks</th>
        </tr>
      </thead>
      <tbody>
        {items.map((c) => (
          <tr key={c.sequence} id={rowId(c.sequence)} className={c.sequence === focused ? 'focused' : undefined}
            aria-current={c.sequence === focused ? 'true' : undefined}>
            <td>{c.sequence}</td>
            <td><Time value={c.occurredAt} /></td>
            <td>{c.agentId}</td>
            <td>{c.erased ? <em>erased</em> : (c.principalId ?? '-')}</td>
            <td>{c.action}</td>
            <td>{c.erased ? <em>erased</em> : (c.target ?? '-')}</td>
            <td><Badge value={c.decision} /></td>
            <td><Badge value={c.outcome} /></td>
            <td><Hash value={c.hash} /></td>
            <td><Checks citation={c} /></td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export function AskPage({ endpoint }: { endpoint: string }) {
  const { api } = useSession()
  const [question, setQuestion] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [result, setResult] = useState<AskAnswer | null>(null)
  const [focused, setFocused] = useState<number | null>(null)

  useEffect(() => {
    if (focused !== null) {
      document.getElementById(rowId(focused))?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
    }
  }, [focused])

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    setFocused(null)
    try {
      setResult(await api.ask(endpoint, question.trim()))
    } catch (reason) {
      setResult(null)
      setError(reason)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Card title="Ask the evidence">
        <form className="stack" onSubmit={submit}>
          <label>Question
            <textarea value={question} maxLength={MAX_LENGTH} rows={3} onChange={(e) => setQuestion(e.target.value)} />
          </label>
          <div className="ask-bar">
            <ul className="chips" aria-label="Examples">
              {EXAMPLES.map((example) => (
                <li key={example}>
                  <button type="button" className="chip" onClick={() => setQuestion(example)}>{example}</button>
                </li>
              ))}
            </ul>
            <span className="muted">{question.length}/{MAX_LENGTH}</span>
            <button type="submit" disabled={busy || question.trim() === ''} aria-busy={busy}>
              {busy ? 'Asking…' : 'Ask'}
            </button>
          </div>
        </form>
        <p className="muted">The answer cites the ledger entries it is based on. Only entries that pass the content, link
          and inclusion checks are used.</p>
        <ErrorMessage error={error} />
        {result && <Answer result={result} onCite={setFocused} />}
      </Card>
      {result && result.citations.length > 0 && (
        <Card title="Cited evidence">
          <CitedEvidence items={result.citations} focused={focused} />
        </Card>
      )}
    </>
  )
}
