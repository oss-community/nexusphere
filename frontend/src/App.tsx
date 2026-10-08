import { BrowserRouter, Navigate, NavLink, Outlet, Route, Routes } from 'react-router'
import { SessionProvider, useSession } from './auth/session'
import { AgentsPage } from './pages/AgentsPage'
import { Callback } from './pages/Callback'
import { EvidencePage } from './pages/EvidencePage'
import { ExchangesPage } from './pages/ExchangesPage'
import { GrantsPage } from './pages/GrantsPage'
import { MyGrantsPage } from './pages/MyGrantsPage'
import { Overview } from './pages/Overview'
import { PackagesPage } from './pages/PackagesPage'
import { SignIn } from './pages/SignIn'

const OPERATOR_LINKS = [
  { to: '/', label: 'Overview' },
  { to: '/evidence', label: 'Evidence' },
  { to: '/agents', label: 'Agents' },
  { to: '/grants', label: 'Grants' },
  { to: '/packages', label: 'Packages' },
  { to: '/exchanges', label: 'A2A exchanges' },
]

const PRINCIPAL_LINKS = [
  { to: '/my/grants', label: 'My grants' },
  { to: '/my/evidence', label: 'Evidence about me' },
]

function Layout() {
  const { session, signOut } = useSession()
  const links = session?.kind === 'principal' ? PRINCIPAL_LINKS : OPERATOR_LINKS
  return (
    <div className="shell">
      <header className="topbar">
        <span className="brand">Nexusphere Ledger</span>
        <nav>
          {links.map((link) => (
            <NavLink key={link.to} to={link.to} end>{link.label}</NavLink>
          ))}
        </nav>
        <span className="who">{session?.kind === 'principal' ? session.principal.name : 'Operator'}</span>
        <button type="button" className="secondary" onClick={() => signOut()}>Sign out</button>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  )
}

function Guard({ kind }: { kind: 'operator' | 'principal' }) {
  const { session, loading } = useSession()
  if (loading) {
    return <p className="muted center">Loading…</p>
  }
  if (!session) {
    return <Navigate to="/sign-in" replace />
  }
  if (session.kind !== kind) {
    return <Navigate to={session.kind === 'principal' ? '/my/grants' : '/'} replace />
  }
  return <Outlet />
}

function SignInRoute() {
  const { session, loading } = useSession()
  if (loading) {
    return <p className="muted center">Loading…</p>
  }
  if (session) {
    return <Navigate to={session.kind === 'principal' ? '/my/grants' : '/'} replace />
  }
  return <SignIn />
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/sign-in" element={<SignInRoute />} />
      <Route path="/callback" element={<Callback />} />
      <Route element={<Layout />}>
        <Route element={<Guard kind="operator" />}>
          <Route index element={<Overview />} />
          <Route path="/evidence" element={<EvidencePage />} />
          <Route path="/agents" element={<AgentsPage />} />
          <Route path="/grants" element={<GrantsPage />} />
          <Route path="/packages" element={<PackagesPage />} />
          <Route path="/exchanges" element={<ExchangesPage />} />
        </Route>
        <Route element={<Guard kind="principal" />}>
          <Route path="/my/grants" element={<MyGrantsPage />} />
          <Route path="/my/evidence" element={<EvidencePage mine />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export function App() {
  return (
    <SessionProvider>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </SessionProvider>
  )
}
