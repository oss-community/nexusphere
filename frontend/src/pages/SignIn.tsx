import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { userManager } from '../auth/oidc'
import { useSession } from '../auth/session'
import { ErrorMessage } from '../components/ui'

export function SignIn() {
  const { signInAsOperator, signInAsPrincipal } = useSession()
  const navigate = useNavigate()
  const [apiKey, setApiKey] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [oidc, setOidc] = useState<boolean | null>(null)

  useEffect(() => {
    userManager().then((manager) => setOidc(manager !== null)).catch(() => setOidc(false))
  }, [])

  async function operator(event: FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      await signInAsOperator(apiKey.trim())
      navigate('/')
    } catch (reason) {
      setError(reason)
    }
  }

  async function principal() {
    setError(null)
    try {
      await signInAsPrincipal()
    } catch (reason) {
      setError(reason)
    }
  }

  return (
    <div className="sign-in">
      <h1>Nexusphere Ledger</h1>
      <p className="muted">Evidence, grants and consent for the actions of AI agents.</p>
      <div className="sign-in-options">
        <section className="card">
          <h2>Principal</h2>
          <p>Sign in with your organization's account to see the grants your agents work under, approve or deny the
            ones waiting for you, and revoke any of them.</p>
          {oidc === false
            ? <p className="muted">This ledger has no OpenID Connect provider configured.</p>
            : <button type="button" onClick={principal} disabled={oidc === null}>Sign in</button>}
        </section>
        <section className="card">
          <h2>Operator</h2>
          <form onSubmit={operator} className="stack">
            <label>
              Operator API key
              <input type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)}
                autoComplete="off" required />
            </label>
            <button type="submit">Open the ledger</button>
          </form>
        </section>
      </div>
      <ErrorMessage error={error} />
    </div>
  )
}
