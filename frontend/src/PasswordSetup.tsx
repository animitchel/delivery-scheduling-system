import { useEffect, useRef, useState } from 'react';
import { api, ApiError } from './api';
import './styles.css';
const redirectTo = (path: string) => window.location.assign(path);
export function PasswordSetup({ client = api, redirect = redirectTo }: {
  client?: Pick<typeof api, 'session' | 'request' | 'logout'>; redirect?: (path: string) => void;
}) {
  const [ready, setReady] = useState(false);
  const [current, setCurrent] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const locked = useRef(false);
  const alive = useRef(true);
  const pending = useRef<AbortController | null>(null);
  useEffect(() => {
    alive.current = true;
    let active = true;
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), 15000);
    void client.session(controller.signal).then(() => { if (!controller.signal.aborted) setReady(true); }).catch((failure: unknown) => {
      if (!active || !alive.current) return;
      if (failure instanceof ApiError && failure.status === 401) redirect('/app/login');
      else setError('We could not check your session. Refresh it before continuing.');
    }).finally(() => window.clearTimeout(timer));
    return () => { active = false; alive.current = false; controller.abort(); pending.current?.abort(); window.clearTimeout(timer); };
  }, [client, redirect, attempt]);
  async function save(event: React.SubmitEvent<HTMLFormElement>) {
    event.preventDefault(); if (locked.current || !ready) return;
    if (password !== confirmation) { setError('New passwords must match.'); return; }
    if (password === current || [...password].length < 15 || new TextEncoder().encode(password).length > 72) {
      setError('Choose a different password with at least 15 characters and at most 72 UTF-8 bytes.'); return;
    }
    locked.current = true; setBusy(true); setError('');
    const controller = new AbortController(); pending.current = controller;
    const timer = window.setTimeout(() => controller.abort(), 15000);
    try {
      await client.request('/api/auth/password', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ currentPassword: current, newPassword: password }), signal: controller.signal });
      if (alive.current && !controller.signal.aborted) redirect('/app/login?passwordChanged');
    } catch (failure) {
      if (!alive.current) return;
      if (failure instanceof ApiError && failure.status === 401) redirect('/app/login');
      else setError(failure instanceof ApiError && failure.status === 403 ? 'Current password verification failed.' : failure instanceof ApiError && failure.status === 400 ? 'Choose a valid, different password and try again.' : 'The change could not be confirmed. Sign in again before retrying.');
    } finally {
      window.clearTimeout(timer); locked.current = false;
      if (alive.current) { setCurrent(''); setPassword(''); setConfirmation(''); setBusy(false); }
    }
  }
  async function signOut() {
    if (locked.current) return;
    locked.current = true; setBusy(true);
    try { await client.logout(); if (alive.current) redirect('/app/login?loggedOut'); }
    catch { if (alive.current) setError('Sign out could not be completed. Please try again.'); }
    finally { locked.current = false; if (alive.current) { setBusy(false); setCurrent(''); setPassword(''); setConfirmation(''); } }
  }
  return <main className="login-page password-setup-page">
    <section className="login-story" aria-label="Your private workspace"><div className="login-brand"><span aria-hidden="true">↗</span> Dispatch</div>
      <div><p className="login-kicker">Your next step</p><h1>A fresh password.<br />A clear start.</h1><p>Make your account your own, then get back to the deliveries ahead.</p></div>
      <div className="password-story-note"><span aria-hidden="true">◇</span><p>Choose a password only you know. Keep it somewhere safe.</p></div>
    </section>
    <section className="login-form-panel" aria-labelledby="password-title"><div className="login-form-wrap">
      <p className="login-kicker">Account security</p><h2 id="password-title">Set your password.</h2><p className="login-description">Enter your current or temporary password, then choose a new one. You’ll sign in again after saving.</p>
      {!ready && !error && <p role="status">Checking your session…</p>}
      <form id="passwordForm" onSubmit={event => { void save(event); }}>
        <label htmlFor="currentPassword">Current password</label><input id="currentPassword" name="currentPassword" type="password" autoComplete="current-password" required disabled={!ready || busy} value={current} onChange={event => setCurrent(event.target.value)} />
        <label htmlFor="newPassword">New password</label><div className="login-password"><input id="newPassword" name="newPassword" type={visible ? 'text' : 'password'} autoComplete="new-password" required disabled={!ready || busy} value={password} onChange={event => setPassword(event.target.value)} /><button type="button" aria-label={visible ? 'Hide new password' : 'Show new password'} aria-pressed={visible} onClick={() => setVisible(!visible)}>{visible ? 'Hide' : 'Show'}</button></div>
        <p className="password-guidance">At least 15 characters. A memorable passphrase works well.</p>
        <label htmlFor="confirmPassword">Confirm new password</label><input id="confirmPassword" type="password" autoComplete="new-password" required disabled={!ready || busy} value={confirmation} onChange={event => setConfirmation(event.target.value)} />
        <p id="passwordMessage" role={error ? 'alert' : 'status'} aria-live="polite">{error || (busy ? 'Working…' : '')}</p>
        <button id="passwordButton" className="button login-submit" disabled={!ready || busy}>{busy ? 'Please wait…' : 'Save password'}<span aria-hidden="true">↗</span></button>
      </form>
      {!ready && error && <button className="text-button" onClick={() => { setError(''); setAttempt(attempt + 1); }}>Refresh session</button>}
      <div className="password-session-actions"><button id="logoutButton" className="text-button" disabled={busy} onClick={() => { void signOut(); }}>Sign out</button><span>Need help? Contact your administrator.</span></div>
      <p className="login-footer">Saving your password signs out all existing sessions.</p>
    </div></section>
  </main>;
}
