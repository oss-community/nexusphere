import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError, LedgerApi } from '../api/client'
import type { Principal } from '../api/types'
import { bearerOf, userManager } from './oidc'

const OPERATOR_KEY = 'nexusphere.operatorKey'

export type Session =
  | { kind: 'operator'; token: string }
  | { kind: 'principal'; token: string; principal: Principal }

interface SessionContextValue {
  session: Session | null
  loading: boolean
  api: LedgerApi
  signInAsOperator: (apiKey: string) => Promise<void>
  signInAsPrincipal: () => Promise<void>
  completePrincipalSignIn: () => Promise<void>
  signOut: () => Promise<void>
}

const SessionContext = createContext<SessionContextValue | null>(null)

function read(key: string): string | null {
  try {
    return sessionStorage.getItem(key)
  } catch {
    return null
  }
}

function write(key: string, value: string | null) {
  try {
    if (value === null) {
      sessionStorage.removeItem(key)
    } else {
      sessionStorage.setItem(key, value)
    }
  } catch {
    return
  }
}

async function principalSession(token: string): Promise<Session> {
  const principal = await new LedgerApi(token).principal()
  return { kind: 'principal', token, principal }
}

export function SessionProvider({ children, initial }: { children: ReactNode; initial?: Session }) {
  const [session, setSession] = useState<Session | null>(initial ?? null)
  const [loading, setLoading] = useState(initial === undefined)

  useEffect(() => {
    if (initial !== undefined) {
      return
    }
    let cancelled = false
    async function restore() {
      const operatorKey = read(OPERATOR_KEY)
      if (operatorKey) {
        return { kind: 'operator', token: operatorKey } as Session
      }
      if (window.location.pathname === '/callback') {
        return null
      }
      const manager = await userManager().catch(() => null)
      const user = manager ? await manager.getUser() : null
      if (user && !user.expired) {
        return principalSession(bearerOf(user)).catch(() => null)
      }
      return null
    }
    restore().then((restored) => {
      if (!cancelled) {
        setSession(restored)
        setLoading(false)
      }
    })
    return () => {
      cancelled = true
    }
  }, [initial])

  const signInAsOperator = useCallback(async (apiKey: string) => {
    const api = new LedgerApi(apiKey)
    try {
      await api.agents()
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        throw new ApiError(403, 'NOT_OPERATOR', 'This key belongs to an agent, not to the ledger operator.')
      }
      throw error
    }
    write(OPERATOR_KEY, apiKey)
    setSession({ kind: 'operator', token: apiKey })
  }, [])

  const signInAsPrincipal = useCallback(async () => {
    const manager = await userManager()
    if (!manager) {
      throw new ApiError(404, 'OIDC_DISABLED', 'This ledger has no OpenID Connect provider configured.')
    }
    await manager.signinRedirect()
  }, [])

  const completePrincipalSignIn = useCallback(async () => {
    const manager = await userManager()
    if (!manager) {
      throw new ApiError(404, 'OIDC_DISABLED', 'This ledger has no OpenID Connect provider configured.')
    }
    const user = await manager.signinRedirectCallback()
    setSession(await principalSession(bearerOf(user)))
  }, [])

  const signOut = useCallback(async () => {
    write(OPERATOR_KEY, null)
    const current = session
    setSession(null)
    if (current?.kind === 'principal') {
      const manager = await userManager().catch(() => null)
      await manager?.removeUser()
    }
  }, [session])

  const value = useMemo<SessionContextValue>(() => ({
    session,
    loading,
    api: new LedgerApi(session?.token ?? null),
    signInAsOperator,
    signInAsPrincipal,
    completePrincipalSignIn,
    signOut,
  }), [session, loading, signInAsOperator, signInAsPrincipal, completePrincipalSignIn, signOut])

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext)
  if (!value) {
    throw new Error('useSession must be used inside SessionProvider')
  }
  return value
}
