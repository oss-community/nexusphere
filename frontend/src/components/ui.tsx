import { useCallback, useEffect, useState, type ReactNode } from 'react'

export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) {
    return null
  }
  const message = error instanceof Error ? error.message : String(error)
  return <p className="error" role="alert">{message}</p>
}

const TONES: Record<string, string> = {
  ACTIVE: 'good', ALLOW: 'good', SUCCEEDED: 'good', VERIFIED: 'good', VALID: 'good', ISSUED: 'good',
  PENDING: 'wait', NOT_YET_VALID: 'wait',
  DENY: 'bad', DENIED: 'bad', FAILED: 'bad', REVOKED: 'bad', INVALID: 'bad', DISABLED: 'bad', MISSING: 'bad',
  EXPIRED: 'muted', EXHAUSTED: 'muted', RETIRED: 'muted',
}

export function Badge({ value }: { value: string | null | undefined }) {
  if (!value) {
    return <span className="muted">-</span>
  }
  return <span className={`badge ${TONES[value] ?? 'muted'}`}>{value}</span>
}

export function Hash({ value }: { value: string | null | undefined }) {
  if (!value) {
    return <span className="muted">-</span>
  }
  return <code title={value}>{value.length > 16 ? `${value.slice(0, 12)}…` : value}</code>
}

export function Time({ value }: { value: string | null | undefined }) {
  if (!value) {
    return <span className="muted">-</span>
  }
  return <time dateTime={value} title={value}>{new Date(value).toLocaleString()}</time>
}

export function Card({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      <header>
        <h2>{title}</h2>
        {actions && <div className="actions">{actions}</div>}
      </header>
      {children}
    </section>
  )
}

export function useLoad<T>(load: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(true)
  const [version, setVersion] = useState(0)
  const run = useCallback(load, deps)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    run().then((value) => {
      if (!cancelled) {
        setData(value)
        setError(null)
      }
    }).catch((reason: unknown) => {
      if (!cancelled) {
        setError(reason)
      }
    }).finally(() => {
      if (!cancelled) {
        setLoading(false)
      }
    })
    return () => {
      cancelled = true
    }
  }, [run, version])

  const reload = useCallback(() => setVersion((v) => v + 1), [])
  return { data, error, loading, reload, setData }
}

export function inOneHour(): string {
  const date = new Date(Date.now() + 60 * 60 * 1000)
  date.setSeconds(0, 0)
  return toLocalInput(date)
}

export function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function splitList(value: string): string[] {
  return value.split(',').map((part) => part.trim()).filter(Boolean)
}
