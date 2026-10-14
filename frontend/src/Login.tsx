import { useEffect, useRef, useState } from 'react';
import { api, ApiError } from './api';
import './styles.css';
export function Login({ client = api, redirect = (path: string) => window.location.assign(path) }: {
  client?: Pick<typeof api, 'login'>; redirect?: (path: string) => void;
}) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const pending = useRef<AbortController | null>(null);
  const locked = useRef(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; pending.current?.abort(); }; }, []);
  const parameters = new URLSearchParams(window.location.search);
  const notice = parameters.has('passwordChanged') ? 'Password saved. Sign in with your new password.' : parameters.has('loggedOut') ? 'You have signed out.' : '';
  async function signIn(event: React.SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (locked.current) return;
    locked.current = true; setBusy(true); setError('');
    const controller = new AbortController(); pending.current = controller;
    const timer = window.setTimeout(() => controller.abort(), 15000);
    try {
      const account = await client.login(username, password, controller.signal);
      if (controller.signal.aborted || !alive.current) return;
      redirect(account.passwordChangeRequired ? '/html/change-password.html' : account.role === 'ADMIN' ? '/app/admin' : '/app/driver/routes');
    } catch (failure) {
      if (!alive.current || pending.current !== controller) return;
      setError(failure instanceof ApiError && failure.status === 401 ? 'Invalid username or password.' : 'Sign in could not be completed. Please try again.');
    } finally {
      window.clearTimeout(timer); locked.current = false; if (alive.current) { setPassword(''); setBusy(false); }
    }
  }
  return <main className="login-page">
    <section className="login-story" aria-label="Dispatch delivery workspace">
      <a className="login-brand" href="/app/"><span aria-hidden="true">↗</span> Dispatch</a>
      <div><p className="login-kicker">A clearer day ahead</p><h1>Every delivery.<br />One connected workspace.</h1><p>Plan the work. Connect your drivers. Keep each stop moving.</p></div>
      <div className="login-route" aria-hidden="true"><span>01</span><i /><span>02</span><i /><span>03</span></div>
      <p className="login-story-footer">From the first assignment to the final handover.</p>
    </section>
    <section className="login-form-panel" aria-labelledby="login-title"><div className="login-form-wrap">
      <p className="login-kicker">Your delivery workspace</p><h2 id="login-title">Welcome back.</h2><p className="login-description">Sign in to pick up where you left off.</p>
      {notice && <p className="login-notice" role="status">{notice}</p>}
      <form id="loginForm" onSubmit={event => { void signIn(event); }}>
        <label htmlFor="username">Username</label><input id="username" name="username" autoComplete="username" autoCapitalize="none" spellCheck={false} required maxLength={64} value={username} disabled={busy} onChange={event => setUsername(event.target.value)} />
        <label htmlFor="password">Password</label><div className="login-password"><input id="password" name="password" type={visible ? 'text' : 'password'} autoComplete="current-password" required value={password} disabled={busy} onChange={event => setPassword(event.target.value)} /><button type="button" aria-label={visible ? 'Hide password' : 'Show password'} aria-pressed={visible} onClick={() => setVisible(!visible)}>{visible ? 'Hide' : 'Show'}</button></div>
        <p id="loginMessage" role={error ? 'alert' : 'status'} aria-live="polite">{error || (busy ? 'Signing in…' : '')}</p>
        <button id="loginButton" className="button login-submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}<span aria-hidden="true">↗</span></button>
      </form>
      <p className="login-help">Need access or a password reset? Contact your administrator.</p>
      <p className="login-footer">A focused workspace for your next delivery.</p>
    </div></section>
  </main>;
}
