import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api, ApiError, roleLabel } from './api'
import type { Login, User } from './api'
import 'bootstrap/dist/css/bootstrap.min.css'
import './App.css'
import { AccountDialog } from './AccountDialog'
import { NodeManagement } from './NodeManagement'

type Filter = 'all' | 'pending' | 'requests'
const tokenKey = 'microgrid.session'
const allowed = (user: User) => user.role === 'BACKOFFICE' || user.role === 'GRID_OPERATOR'

function App() {
  const [token, setToken] = useState<string | null>(() => sessionStorage.getItem(tokenKey))
  const [user, setUser] = useState<User | null>(null)
  const [users, setUsers] = useState<User[]>([])
  const [area, setArea] = useState<'nodes' | 'accounts'>('nodes')
  const [filter, setFilter] = useState<Filter>('all')
  const [search, setSearch] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [loading, setLoading] = useState(Boolean(token))
  const [busy, setBusy] = useState(false)
  const [editor, setEditor] = useState<User | 'new' | null>(null)
  const [confirmation, setConfirmation] = useState<User | null>(null)

  const signOut = useCallback(() => {
    sessionStorage.removeItem(tokenKey)
    setToken(null); setUser(null); setUsers([]); setEditor(null); setConfirmation(null); setNotice(''); setArea('nodes')
  }, [])
  function report(reason: unknown) {
    if (reason instanceof ApiError && reason.status === 401) signOut()
    setError(reason instanceof Error ? reason.message : 'Something went wrong. Please retry.')
  }
  useEffect(() => {
    if (!token) return
    let active = true
    async function restore() {
      try {
        const current = await api<User>('/user', token)
        if (!allowed(current)) throw new ApiError('Use the Android app for your prosumer account.', 401)
        const accounts = current.role === 'BACKOFFICE' ? await api<User[]>('/users', token) : []
        if (active) { setUser(current); setUsers(accounts); setError('') }
      } catch (reason) {
        if (active) {
          if (reason instanceof ApiError && reason.status === 401) {
            sessionStorage.removeItem(tokenKey); setToken(null); setUser(null); setUsers([])
          }
          setError(reason instanceof Error ? reason.message : 'Unable to restore your session.')
        }
      } finally { if (active) setLoading(false) }
    }
    void restore()
    const onFocus = () => { void restore() }
    window.addEventListener('focus', onFocus)
    return () => { active = false; window.removeEventListener('focus', onFocus) }
  }, [token])

  async function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('')
    const form = new FormData(event.currentTarget)
    try {
      const result = await api<Login>('/login', null, 'POST', { email: form.get('email'), password: form.get('password') })
      if (!allowed(result.user)) throw new Error('Prosumer accounts use the Android app. Sign in there to manage your account.')
      sessionStorage.setItem(tokenKey, result.token); setLoading(true); setToken(result.token)
    } catch (reason) { report(reason) } finally { setBusy(false) }
  }
  async function refresh() {
    const current = await api<User>('/user', token)
    setUser(current)
    if (current.role === 'BACKOFFICE') setUsers(await api<User[]>('/users', token))
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('')
    const form = new FormData(event.currentTarget)
    const profile = { userName: form.get('userName'), email: form.get('email') }
    try {
      if (editor === 'new') await api('/back-office/users', token, 'POST', { ...profile, nic: form.get('nic'), password: form.get('password'), role: form.get('role') })
      else if (editor) await api(editor.id === user?.id ? '/user' : `/back-office/${encodeURIComponent(editor.id)}`, token, 'PATCH', profile)
      setEditor(null); setNotice('Account saved successfully.'); await refresh()
    } catch (reason) { report(reason) } finally { setBusy(false) }
  }
  async function status(account: User) {
    setBusy(true); setError(''); setNotice('')
    try {
      await api(`/back-office/${encodeURIComponent(account.id)}/status?active=${!account.activation}`, token, 'PATCH')
      setConfirmation(null); setNotice(`${account.userName} ${account.activation ? 'deactivated' : 'activated'} successfully.`); await refresh()
    } catch (reason) { report(reason) } finally { setBusy(false) }
  }
  const pending = users.filter(account => account.activationPending)
  const requests = users.filter(account => account.deactivationRequested)
  const displayed = users.filter(account => (filter === 'all' || (filter === 'pending' ? account.activationPending : account.deactivationRequested)) &&
    `${account.userName} ${account.email} ${account.nic}`.toLowerCase().includes(search.toLowerCase()))

  return <div className="app-shell">
    <header className="site-header"><div className="container d-flex align-items-center justify-content-between gap-3">
      <a className="brand" href="/">SolarGrid <span className="brand-caption">MICROGRID NETWORK</span></a>
      {user && <button className="btn btn-outline-light btn-sm" disabled={busy} onClick={signOut}>Sign out</button>}
    </div></header>
    <main className="container py-4 py-lg-5">
      {error && <div className="alert alert-danger" role="alert">{error}</div>}
      {notice && <div className="alert alert-success" role="status">{notice}</div>}
      {loading ? <div className="panel p-5 text-center" role="status">Checking your account...</div> : !user ?
        <div className="row align-items-center g-5 login-layout">
          <section className="col-lg-7"><span className="eyebrow">CONNECTED ENERGY. SHARED OPPORTUNITY.</span><h1 className="display-4 fw-semibold mt-3">A brighter grid starts<br />with connected people.</h1><p className="lead text-secondary mt-4">Manage your microgrid community, approve new prosumers, and keep account access in the right hands.</p><div className="login-note mt-4">Staff portal - Backoffice & Grid Operators<br /><span>Prosumers: register and sign in using the Android app.</span></div></section>
          <section className="col-lg-5"><div className="panel p-4 p-lg-5"><span className="eyebrow">WELCOME BACK</span><h2 className="mt-2">Sign in to SolarGrid</h2><p className="text-secondary">Use your activated staff account.</p><form onSubmit={login}>
            <label className="form-label" htmlFor="email">Email address</label><input className="form-control mb-3" id="email" name="email" type="email" autoComplete="username" required maxLength={254} />
            <label className="form-label" htmlFor="password">Password</label><input className="form-control mb-4" id="password" name="password" type="password" autoComplete="current-password" required />
            <button className="btn btn-primary w-100" disabled={busy}>{busy ? 'Signing in...' : 'Sign in'}</button>
          </form><p className="small text-secondary mt-4 mb-0">Need access? Contact your Backoffice officer.</p></div></section>
        </div> : <>
          <div className="d-flex flex-wrap align-items-start justify-content-between gap-3 mb-4"><div><span className="eyebrow">{roleLabel(user.role)} WORKSPACE</span><h1 className="mt-2">Welcome, {user.userName}</h1><p className="text-secondary">{user.role === 'BACKOFFICE' ? 'Manage your solar community and microgrid hubs.' : 'Review grid hubs and manage battery-slot availability.'}</p></div><button className="btn btn-outline-secondary" onClick={() => setEditor(user)}>My profile</button></div>
          <nav className="nav nav-pills gap-2 mb-4" aria-label="Workspace sections"><button className={`nav-link ${area === 'nodes' ? 'active' : ''}`} onClick={() => setArea('nodes')}>Grid nodes</button>{user.role === 'BACKOFFICE' && <button className={`nav-link ${area === 'accounts' ? 'active' : ''}`} onClick={() => setArea('accounts')}>User accounts</button>}</nav>
          {area === 'nodes' ? <NodeManagement key={user.id} token={token!} role={user.role} onUnauthorized={signOut} /> : user.role === 'BACKOFFICE' ? <>
            <div className="row g-3 mb-4">{([{ label: 'Community accounts', count: users.length, value: 'all' }, { label: 'Pending activation', count: pending.length, value: 'pending' }, { label: 'Deactivation requests', count: requests.length, value: 'requests' }] as const).map(item => <div className="col-md-4" key={item.value}><button className={`stat-card panel w-100 text-start ${filter === item.value ? 'selected' : ''}`} onClick={() => setFilter(item.value)} aria-pressed={filter === item.value}><span>{item.label}</span><strong>{item.count}</strong><span className="small">View accounts</span></button></div>)}</div>
            <section className="panel p-3 p-lg-4"><div className="d-flex flex-wrap justify-content-between gap-3 mb-4"><h2 className="h4 mb-0">{filter === 'pending' ? 'Activation queue' : filter === 'requests' ? 'Deactivation requests' : 'User directory'}</h2><button className="btn btn-primary" onClick={() => setEditor('new')}>+ Create account</button></div><div className="d-flex gap-2 mb-3"><input type="search" className="form-control" aria-label="Search accounts" placeholder="Search by name, email or NIC" value={search} onChange={event => setSearch(event.target.value)} /><button className="btn btn-outline-secondary" disabled={busy} onClick={() => { setBusy(true); void refresh().catch(report).finally(() => setBusy(false)) }}>Refresh</button></div>
            <div className="table-responsive"><table className="table align-middle"><thead><tr><th>Account</th><th>NIC</th><th>Role</th><th>Status</th><th>Actions</th></tr></thead><tbody>{displayed.map(account => <tr key={account.id}><td><strong>{account.userName}</strong><div className="small text-secondary">{account.email}</div></td><td className="text-nowrap">{account.nic}</td><td>{roleLabel(account.role)}</td><td><span className={`badge ${account.activation ? 'text-bg-success' : 'text-bg-secondary'}`}>{account.activationPending ? 'Pending activation' : account.activation ? 'Active' : 'Deactivated'}</span>{account.deactivationRequested && <div className="small text-warning-emphasis mt-1">Deactivation requested</div>}</td><td><div className="d-flex gap-2"><button className="btn btn-sm btn-outline-secondary" onClick={() => setEditor(account)}>Edit</button><button className={`btn btn-sm ${account.activation ? 'btn-outline-danger' : 'btn-outline-success'}`} disabled={busy || account.id === user.id} onClick={() => setConfirmation(account)}>{account.activation ? 'Deactivate' : account.activationPending ? 'Approve' : 'Reactivate'}</button></div></td></tr>)}</tbody></table></div>{displayed.length === 0 && <p className="text-center text-secondary py-4">No accounts match this view.</p>}</section>
          </> : <section className="panel p-4"><h2 className="h4">Grid Operator home</h2><p className="text-secondary mb-0">Manage your profile here, or sign in to the Android app with the same account.</p></section>}
        </>}
    </main><footer className="container py-4 small text-secondary">SolarGrid - Smart Solar Microgrid Trading System</footer>
    {editor && <AccountDialog titleId="editor-title" busy={busy} onClose={() => { setEditor(null); setError('') }}><h2 id="editor-title" className="h4">{editor === 'new' ? 'Create account' : 'Edit profile'}</h2>{error && <div role="alert" className="alert alert-danger">{error}</div>}<form onSubmit={save} key={editor === 'new' ? 'new' : editor.id}>
      <label className="form-label" htmlFor="edit-name">Full name</label><input autoFocus id="edit-name" name="userName" className="form-control mb-3" required minLength={2} maxLength={100} defaultValue={editor === 'new' ? '' : editor.userName} />
      <label className="form-label" htmlFor="edit-email">Email address</label><input id="edit-email" name="email" className="form-control mb-3" type="email" required maxLength={254} defaultValue={editor === 'new' ? '' : editor.email} />
      {editor === 'new' ? <><label className="form-label" htmlFor="edit-nic">NIC</label><input id="edit-nic" name="nic" className="form-control mb-3" required pattern="([0-9]{12}|[0-9]{9}[vVxX])" title="12 digits, or 9 digits followed by V or X" /><label className="form-label" htmlFor="edit-role">Role</label><select id="edit-role" name="role" className="form-select mb-3" defaultValue="PROSUMER"><option value="PROSUMER">Prosumer</option><option value="GRID_OPERATOR">Grid Operator</option><option value="BACKOFFICE">Backoffice</option></select><label className="form-label" htmlFor="edit-password">Initial password</label><input id="edit-password" name="password" className="form-control mb-3" type="password" autoComplete="new-password" required minLength={8} maxLength={72} /><p className="small text-secondary">This account will be active immediately. Share credentials securely with its owner.</p></> : <p className="small text-secondary">NIC {editor.nic} - {roleLabel(editor.role)}. Account identity and role cannot be changed here.</p>}
      <div className="d-flex justify-content-end gap-2 mt-4"><button type="button" className="btn btn-outline-secondary" disabled={busy} onClick={() => { setEditor(null); setError('') }}>Cancel</button><button className="btn btn-primary" disabled={busy}>{busy ? 'Saving...' : 'Save account'}</button></div>
    </form></AccountDialog>}
    {confirmation && <AccountDialog titleId="confirm-title" busy={busy} onClose={() => setConfirmation(null)}><h2 className="h4" id="confirm-title">{confirmation.activation ? 'Deactivate' : 'Activate'} {confirmation.userName}?</h2><p>{confirmation.activation ? 'Their active sessions will stop working. Only Backoffice can reactivate this account.' : 'The account owner will be able to sign in.'}</p>{error && <p role="alert" className="text-danger">{error}</p>}<div className="d-flex justify-content-end gap-2"><button className="btn btn-outline-secondary" disabled={busy} onClick={() => setConfirmation(null)}>Cancel</button><button className="btn btn-primary" disabled={busy} onClick={() => void status(confirmation)}>{busy ? 'Saving...' : 'Confirm'}</button></div></AccountDialog>}
  </div>
}
export default App
