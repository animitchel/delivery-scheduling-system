import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, Navigate, Route, Routes } from 'react-router-dom';
import { api, ApiError } from './api';
import type { Account, Role, SessionClient } from './api';
import './styles.css';

type Session = { kind: 'loading' } | { kind: 'anonymous' } | { kind: 'error' } | { kind: 'ready'; account: Account };
const redirectTo = (path: string) => window.location.assign(path);
const home = (role: Role) => role === 'ADMIN' ? '/admin' : '/driver';
export function App({ client = api, redirect = redirectTo }: { client?: SessionClient; redirect?: (path: string) => void }) {
  const [session, setSession] = useState<Session>({ kind: 'loading' });
  const [signingOut, setSigningOut] = useState(false);
  const [logoutError, setLogoutError] = useState(false);
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
      if (account.passwordChangeRequired) { setSession({ kind: 'anonymous' }); redirect('/html/change-password.html'); return; }
      setSession({ kind: 'ready', account });
    }).catch((error: unknown) => {
      if (controller.signal.aborted || request !== sequence.current) return;
      if (error instanceof ApiError && error.passwordChangeRequired) { setSession({ kind: 'anonymous' }); redirect('/html/change-password.html'); }
      else setSession(error instanceof ApiError && error.status === 401 ? { kind: 'anonymous' } : { kind: 'error' });
    });
  }, [client, redirect]);
  const refresh = useCallback(() => {
    if (busy.current) return;
    setSession({ kind: 'loading' }); setLogoutError(false); void load();
  }, [load]);
  useEffect(() => {
    void load();
    const visible = () => { if (document.visibilityState === 'visible') refresh(); };
    window.addEventListener('focus', refresh); document.addEventListener('visibilitychange', visible);
    return () => { cancelLoad(); window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', visible); };
  }, [load, refresh, cancelLoad]);
  async function signOut() {
    if (busy.current) return;
    busy.current = true; setSigningOut(true); setLogoutError(false);
    cancelLoad();
    try { await client.logout(); setSession({ kind: 'anonymous' }); redirect('/html/login.html?loggedOut'); }
    catch (error) {
      if (error instanceof ApiError && error.status === 401) setSession({ kind: 'anonymous' });
      else if (error instanceof ApiError && error.passwordChangeRequired) { setSession({ kind: 'anonymous' }); redirect('/html/change-password.html'); }
      else setLogoutError(true);
    } finally { busy.current = false; setSigningOut(false); }
  }
  if (session.kind !== 'ready') return <div className="entry"><Brand /><main className="entry-panel">
    {session.kind === 'loading' ? <><p className="eyebrow">Delivery workspace</p><h1>Checking your session…</h1><p role="status">Preparing your workspace.</p></>
      : session.kind === 'anonymous' ? <><p className="eyebrow">Welcome</p><h1>Sign in to your workspace.</h1><p>Access your delivery tools with your existing account.</p><a className="button" href="/html/login.html">Sign in</a></>
      : <><h1>Your workspace couldn’t be loaded.</h1><p role="alert">We couldn’t check your session. Please try again.</p><button className="button" onClick={refresh}>Try again</button></>}
  </main></div>;
  const { account } = session;
  return <div className="workspace"><a className="skip-link" href="#main-content">Skip to content</a>
    <aside className="sidebar"><Brand /><p className="nav-label">Workspace</p><nav aria-label="Main navigation">
      <Link className="nav-home" to={home(account.role)}>Overview</Link>
      {account.role === 'ADMIN' ? <><a href="/html/admin.html">Scheduling</a><a href="/html/accounts.html">Accounts</a><a href="/html/operators.html">Routes</a></> : <a href="/html/operators.html">Assigned routes</a>}
    </nav><div className="sidebar-note"><span className="status-dot" />Signed in securely</div></aside>
    <div className="workspace-body"><header className="topbar"><span className="workspace-label">Delivery workspace</span><div className="session-actions">
      <div className="identity"><span className="username" title={account.username}>{account.username}</span><span className="role-label">{account.role === 'ADMIN' ? 'Administrator' : 'Driver'}</span></div>
      <button className="button secondary" disabled={signingOut} onClick={() => { void signOut(); }}>{signingOut ? 'Signing out…' : 'Sign out'}</button>
    </div></header><main id="main-content" tabIndex={-1}>
      {logoutError && <p className="error-message" role="alert">Sign out failed. Please try again.</p>}
      <Routes><Route index element={<Navigate to={home(account.role)} replace />} />
        <Route path="admin" element={account.role === 'ADMIN' ? <Overview role="ADMIN" /> : <Forbidden destination={home(account.role)} />} />
        <Route path="driver" element={account.role === 'DRIVER' ? <Overview role="DRIVER" /> : <Forbidden destination={home(account.role)} />} />
        <Route path="*" element={<><h1>Page not found.</h1><Link to={home(account.role)}>Return to your workspace</Link></>} />
      </Routes><div className="session-footer"><span>Keep your workspace up to date.</span><button className="text-button" onClick={refresh}>Refresh session</button></div>
    </main></div></div>;
}
function Brand() { return <div className="brand"><span className="brand-mark" aria-hidden="true">↗</span><span>Dispatch<span className="brand-caption">Delivery scheduling</span></span></div>; }
function Overview({ role }: { role: Role }) {
  const admin = role === 'ADMIN';
  const cards = admin ? [
    { title: 'Schedule deliveries', text: 'Manage recipients, prepare deliveries and generate routes.', href: '/html/admin.html', action: 'Open scheduling', symbol: '01' },
    { title: 'Manage accounts', text: 'Create driver access, reset passwords and manage account availability.', href: '/html/accounts.html', action: 'Open accounts', symbol: '02' },
    { title: 'Review routes', text: 'Follow assigned stops and review delivery progress.', href: '/html/operators.html', action: 'Open routes', symbol: '03' },
  ] : [{ title: 'Your assigned routes', text: 'Review your stops, view recipient details and update each delivery as you go.', href: '/html/operators.html', action: 'View assigned routes', symbol: '01' }];
  return <><section className="page-intro"><p className="eyebrow">{admin ? 'Operations overview' : 'Driver overview'}</p><h1>{admin ? 'Keep deliveries moving.' : 'Your next stop starts here.'}</h1>
    <p className="intro-text">{admin ? 'Your tools for planning work, managing access and following progress.' : 'Everything you need to follow your route and keep the team informed.'}</p></section>
    <section className="tools" aria-label="Delivery tools">{cards.map(card => <article className="tool-card" key={card.title}><span className="card-number" aria-hidden="true">{card.symbol}</span><h2>{card.title}</h2><p>{card.text}</p><a href={card.href}>{card.action}<span aria-hidden="true"> ↗</span></a></article>)}</section>
    <section className="workspace-tip"><div><p className="eyebrow">A clear handover</p><h2>{admin ? 'Plan, assign, follow through.' : 'Update each stop as you go.'}</h2></div><p>{admin ? 'Generated routes are assigned to available operators. Track completion through the route view.' : 'Mark each stop completed or cancelled. Your route finishes when every stop has an outcome.'}</p></section></>;
}
function Forbidden({ destination }: { destination: string }) { return <section className="page-intro"><p className="eyebrow">Access restricted</p><h1>You don’t have access to this workspace.</h1><p>Your account has a different workspace.</p><Link className="button" to={destination}>Open your workspace</Link></section>; }
