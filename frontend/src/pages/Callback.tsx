import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useSession } from '../auth/session'
import { ErrorMessage } from '../components/ui'

export function Callback() {
  const { completePrincipalSignIn } = useSession()
  const navigate = useNavigate()
  const [error, setError] = useState<unknown>(null)
  const started = useRef(false)

  useEffect(() => {
    if (started.current) {
      return
    }
    started.current = true
    completePrincipalSignIn().then(() => navigate('/my/grants', { replace: true })).catch(setError)
  }, [completePrincipalSignIn, navigate])

  return (
    <div className="sign-in">
      {error ? <><ErrorMessage error={error} /><Link to="/">Back to sign-in</Link></> : <p>Signing you in…</p>}
    </div>
  )
}
