import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, Navigate, Route, Routes, useSearchParams } from 'react-router-dom';
import { api, ApiError } from './api';
import type { Account, Role, SessionClient } from './api';
import './styles.css';
import { WorkspaceNavigation } from './WorkspaceNavigation';
import { RouteGeneration } from './RouteGeneration';
import { AdminRoutes } from './AdminRoutes';
import { Deliveries } from './Deliveries';
import { Operators } from './Operators';
import { Warehouses } from './Warehouses';
import { Recipients } from './Recipients';
import { Accounts } from './Accounts';
import { DriverRoutes } from './DriverRoutes';

type Session = { kind: 'loading' } | { kind: 'anonymous' } | { kind: 'error' } | { kind: 'ready'; account: Account };
const redirectTo = (path: string) => window.location.assign(path);
const home = (role: Role) => role === 'ADMIN' ? '/admin' : '/driver';
export function App({ client = api, redirect = redirectTo }: { client?: SessionClient; redirect?: (path: string) => void }) {
  const [session, setSession] = useState<Session>({ kind: 'loading' });
  const [signingOut, setSigningOut] = useState(false);
  const [logoutError, setLogoutError] = useState(false);
  const [mutationPending, setMutationPending] = useState(false);
  const mutationBusy = useRef(false);
  const handleBusyChange = useCallback((value: boolean) => { mutationBusy.current = value; setMutationPending(value); }, []);
  const sequence = useRef(0);
  const pending = useRef<AbortController | null>(null);
  const busy = useRef(false);
  const cancelLoad = useCallback(() => { ++sequence.current; pending.current?.abort(); }, []);
  const load = useCallback(() => {
    const request = ++sequence.current;
    pending.current?.abort();
    const controller = new AbortController(); pending.current = controller;
    void client.session(controller.signal).then(account => {
      if (controller.signal.aborted || request !== sequence.current) return;
      if (account.passwordChangeRequired) { setSession({ kind: 'anonymous' }); redirect('/app/change-password'); return; }
      setSession({ kind: 'ready', account });
    }).catch((error: unknown) => {
      if (controller.signal.aborted || request !== sequence.current) return;
      if (error instanceof ApiError && error.passwordChangeRequired) { setSession({ kind: 'anonymous' }); redirect('/app/change-password'); }
      else setSession(error instanceof ApiError && error.status === 401 ? { kind: 'anonymous' } : { kind: 'error' });
    });
  }, [client, redirect]);
  const handleAuthError = useCallback((error: ApiError) => {
    cancelLoad(); setSession({ kind: 'anonymous' });
    if (error.passwordChangeRequired) redirect('/app/change-password');
  }, [cancelLoad, redirect]);
  const refresh = useCallback(() => {
    if (busy.current || mutationBusy.current) return;
    setSession({ kind: 'loading' }); setLogoutError(false); void load();
  }, [load]);
  useEffect(() => {
    void load();
    const visible = () => { if (document.visibilityState === 'visible') refresh(); };
    window.addEventListener('focus', refresh); document.addEventListener('visibilitychange', visible);
    return () => { cancelLoad(); window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', visible); };
  }, [load, refresh, cancelLoad]);
  async function signOut() {
    if (busy.current || mutationBusy.current) return;
    busy.current = true; setSigningOut(true); setLogoutError(false);
    cancelLoad();
    try { await client.logout(); setSession({ kind: 'anonymous' }); redirect('/app/login?loggedOut'); }
    catch (error) {
      if (error instanceof ApiError && error.status === 401) setSession({ kind: 'anonymous' });
      else if (error instanceof ApiError && error.passwordChangeRequired) { setSession({ kind: 'anonymous' }); redirect('/app/change-password'); }
      else setLogoutError(true);
    } finally { busy.current = false; setSigningOut(false); }
  }
  if (session.kind !== 'ready') return <div className="entry"><Brand /><main className="entry-panel">
    {session.kind === 'loading' ? <><p className="eyebrow">Delivery workspace</p><h1>Checking your session…</h1><p role="status">Preparing your workspace.</p></>
      : session.kind === 'anonymous' ? <><p className="eyebrow">Welcome</p><h1>Sign in to your workspace.</h1><p>Access your delivery tools with your existing account.</p><a className="button" href="/app/login">Sign in</a></>
      : <><h1>Your workspace couldn’t be loaded.</h1><p role="alert">We couldn’t check your session. Please try again.</p><button className="button" onClick={refresh}>Try again</button></>}
  </main></div>;
  const { account } = session;
  return <div className="workspace"><a className="skip-link" href="#main-content">Skip to content</a>
    <aside className="sidebar"><Brand /><WorkspaceNavigation role={account.role} disabled={mutationPending} isBlocked={() => mutationBusy.current} />
      <div className="sidebar-note"><span className="status-dot" />Delivery scheduling workspace</div></aside>
    <div className="workspace-body"><header className="topbar"><span className="workspace-label">Delivery workspace</span><div className="session-actions">
      <div className="identity"><span className="username" title={account.username}>{account.username}</span><span className="role-label">{account.role === 'ADMIN' ? 'Administrator' : 'Driver'}</span></div>
      <button className="button secondary" disabled={signingOut || mutationPending} onClick={() => { void signOut(); }}>{signingOut ? 'Signing out…' : 'Sign out'}</button>
    </div></header><main id="main-content" tabIndex={-1}>
      {logoutError && <p className="error-message" role="alert">Sign out failed. Please try again.</p>}
      <Routes><Route index element={<Navigate to={home(account.role)} replace />} />
        <Route path="admin" element={account.role === 'ADMIN' ? <Overview role="ADMIN" /> : <Forbidden destination={home(account.role)} />} />
        <Route path="driver" element={account.role === 'DRIVER' ? <Overview role="DRIVER" /> : <Forbidden destination={home(account.role)} />} />
        <Route path="admin/routes/generate" element={account.role === 'ADMIN' ? <RouteGeneration onAuthError={handleAuthError} onBusyChange={handleBusyChange} /> : <Forbidden destination={home(account.role)} />} />
        <Route path="admin/routes" element={account.role === 'ADMIN' ? <AdminRoutePage onAuthError={handleAuthError} onBusyChange={handleBusyChange} /> : <Forbidden destination={home(account.role)} />} />
        <Route path="admin/deliveries" element={account.role === 'ADMIN' ? <Deliveries onAuthError={handleAuthError} onBusyChange={handleBusyChange} /> : <Forbidden destination={home(account.role)} />} />
        <Route path="admin/operators" element={account.role === 'ADMIN' ? <Operators onAuthError={handleAuthError} onBusyChange={handleBusyChange} /> : <Forbidden destination={home(account.role)} />} />
        <Route path="admin/warehouses" element={account.role === 'ADMIN' ? <Warehouses onAuthError={handleAuthError} onBusyChange={handleBusyChange} /> : <Forbidden destination={home(account.role)} />} />
        <Route path="admin/recipients" element={account.role === 'ADMIN' ? <Recipients onAuthError={handleAuthError} onBusyChange={handleBusyChange} /> : <Forbidden destination={home(account.role)} />} />
        <Route path="admin/accounts" element={account.role === 'ADMIN' ? <Accounts onAuthError={handleAuthError} onBusyChange={handleBusyChange} /> : <Forbidden destination={home(account.role)} />} />
        <Route path="driver/routes" element={account.role === 'DRIVER' ? <DriverRoutes onAuthError={handleAuthError} onBusyChange={handleBusyChange} /> : <Forbidden destination={home(account.role)} />} />
        <Route path="*" element={<><h1>Page not found.</h1><Link to={home(account.role)}>Return to your workspace</Link></>} />
      </Routes><div className="session-footer"><span>Keep your workspace up to date.</span><button className="text-button" disabled={mutationPending} onClick={refresh}>Refresh session</button></div>
    </main></div></div>;
}
function Brand() { return <div className="brand"><span className="brand-mark" aria-hidden="true">↗</span><span>Dispatch<span className="brand-caption">Delivery scheduling</span></span></div>; }
function Overview({ role }: { role: Role }) {
  const admin = role === 'ADMIN';
  const cards = admin ? [
    { title: 'Manage warehouses', text: 'Maintain depot addresses and coordinates for operator setup.', href: '/app/admin/warehouses', action: 'Open warehouses', symbol: '01' },
    { title: 'Manage operators', text: 'Set up driver records, warehouse assignments and availability.', href: '/app/admin/operators', action: 'Open operators', symbol: '02' },
    { title: 'Manage accounts', text: 'Create driver access, reset passwords and manage account availability.', href: '/app/admin/accounts', action: 'Open accounts', symbol: '03' },
    { title: 'Manage recipients', text: 'Find recipients and maintain delivery addresses and contact details.', href: '/app/admin/recipients', action: 'Open recipients', symbol: '04' },
    { title: 'Prepare deliveries', text: 'Choose recipients, create deliveries and review saved work.', href: '/app/admin/deliveries', action: 'Open deliveries', symbol: '05' },
    { title: 'Generate routes', text: 'Assign eligible pending work to available operators and start routes.', href: '/app/admin/routes/generate', action: 'Open route generation', symbol: '06' },
    { title: 'Review routes', text: 'Follow assigned stops and review delivery progress.', href: '/app/admin/routes', action: 'Open routes', symbol: '07' },
  ] : [{ title: 'Your assigned routes', text: 'Review your stops, view recipient details and update each delivery as you go.', href: '/app/driver/routes', action: 'View assigned routes', symbol: '01' }];
  return <><section className="page-intro"><p className="eyebrow">{admin ? 'Operations overview' : 'Driver overview'}</p><h1>{admin ? 'Keep deliveries moving.' : 'Your next stop starts here.'}</h1>
    <p className="intro-text">{admin ? 'Set up your team, prepare deliveries, and follow your routes. Open any tool directly.' : 'Everything you need to follow your route and keep the team informed.'}</p></section>
    <section className="tools" aria-label="Delivery tools">{cards.map(card => <article className="tool-card" key={card.title}><span className="card-number" aria-hidden="true">{card.symbol}</span><h2>{card.title}</h2><p>{card.text}</p><a href={card.href}>{card.action}<span aria-hidden="true"> ↗</span></a></article>)}</section>
    <section className="workspace-tip"><div><p className="eyebrow">A clear handover</p><h2>{admin ? 'Plan, assign, follow through.' : 'Update each stop as you go.'}</h2></div><p>{admin ? 'Generated routes are assigned to available operators. Track completion through the route view.' : 'Mark each stop completed or cancelled. Your route finishes when every stop has an outcome.'}</p></section></>;
}
function Forbidden({ destination }: { destination: string }) { return <section className="page-intro"><p className="eyebrow">Access restricted</p><h1>You don’t have access to this workspace.</h1><p>Your account has a different workspace.</p><Link className="button" to={destination}>Open your workspace</Link></section>; }

function AdminRoutePage({ onAuthError, onBusyChange }: { onAuthError: (error: ApiError) => void; onBusyChange: (busy: boolean) => void }) {
  const [params] = useSearchParams(); const values = params.getAll('routeId'); const value = values[0];
  if (values.length > 1 || value !== undefined && (!/^[1-9][0-9]*$/.test(value) || !Number.isSafeInteger(Number(value)) || Number(value) > 2147483647))
    return <section className="page-intro"><h1>Invalid route link.</h1><p role="alert">Choose a route from the directory.</p><Link to="/admin/routes">Open route directory</Link></section>;
  const id = value === undefined ? undefined : Number(value);
  return <AdminRoutes key={id ?? 'directory'} initialRouteId={id} onAuthError={onAuthError} onBusyChange={onBusyChange} />;
}
