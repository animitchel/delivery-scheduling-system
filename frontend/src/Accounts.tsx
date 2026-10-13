import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from './api';
import { accountsApi } from './accounts-api';
import type { AccountsClient, AccountSummary, Credential, EligibleOperator, Page } from './accounts-api';
import './accounts.css';
type Action = { account: AccountSummary; kind: 'enabled' | 'reset' };
export function Accounts({ client = accountsApi, onAuthError, onBusyChange }: { client?: AccountsClient; onAuthError: (error: ApiError) => void; onBusyChange: (busy: boolean) => void }) {
  const [directory, setDirectory] = useState<Page<AccountSummary> | null>(null);
  const [operators, setOperators] = useState<Page<EligibleOperator> | null>(null);
  const [loading, setLoading] = useState(true);
  const [operatorLoading, setOperatorLoading] = useState(true);
  const [message, setMessage] = useState('');
  const [messageKind, setMessageKind] = useState<'error' | 'success'>('error');
  const [operatorError, setOperatorError] = useState('');
  const [search, setSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');
  const [selected, setSelected] = useState('');
  const [busy, setBusy] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const [action, setAction] = useState<Action | null>(null);
  const [credential, setCredential] = useState<Credential | null>(null);
  const [copyMessage, setCopyMessage] = useState('');
  const active = useRef(true);
  const write = useRef(false);
  const accountRead = useRef<AbortController | null>(null);
  const operatorRead = useRef<AbortController | null>(null);
  const mutation = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const createForm = useRef<HTMLFormElement>(null);
  const actionForm = useRef<HTMLFormElement>(null);
  const actionDialog = useRef<HTMLDialogElement>(null);
  const credentialDialog = useRef<HTMLDialogElement>(null);
  const credentialField = useRef<HTMLInputElement>(null);
  const actionFocus = useRef<HTMLButtonElement | null>(null);
  const auth = useCallback((error: unknown) => {
    if (error instanceof ApiError && (error.status === 401 || error.passwordChangeRequired)) { onAuthError(error); return true; }
    return false;
  }, [onAuthError]);
  const loadAccounts = useCallback((number: number, latest = false) => {
    accountRead.current?.abort(); const controller = new AbortController(); accountRead.current = controller;
    let timedOut = false; const timer = window.setTimeout(() => { timedOut = true; controller.abort(); }, 30000);
    return Promise.resolve().then(async () => {
      let result = await client.accounts(number, controller.signal);
      if (controller.signal.aborted || !active.current) return false;
      if (latest && result.totalPages > 1) result = await client.accounts(result.totalPages - 1, controller.signal);
      else if (number > 0 && number >= result.totalPages) result = await client.accounts(Math.max(0, result.totalPages - 1), controller.signal);
      if (controller.signal.aborted || !active.current) return false;
      setDirectory(result); setLoading(false); return true;
    }).catch((error: unknown) => {
      if ((controller.signal.aborted && !timedOut) || !active.current) return false;
      setDirectory(null); setLoading(false); setBlocked(true); setMessageKind('error');
      if (!auth(error)) setMessage(error instanceof ApiError && error.status === 403 ? 'Administrator access is required.' : 'Accounts could not be loaded. Reload accounts to try again.');
      return false;
    }).finally(() => window.clearTimeout(timer));
  }, [client, auth]);
  const loadOperators = useCallback((number: number, query: string) => {
    operatorRead.current?.abort(); const controller = new AbortController(); operatorRead.current = controller;
    let timedOut = false; const timer = window.setTimeout(() => { timedOut = true; controller.abort(); }, 30000);
    return Promise.resolve().then(async () => {
      let result = await client.operators(number, query, controller.signal);
      if (controller.signal.aborted || !active.current) return false;
      if (number > 0 && number >= result.totalPages) result = await client.operators(Math.max(0, result.totalPages - 1), query, controller.signal);
      if (controller.signal.aborted || !active.current) return false;
      setOperators(result); setOperatorLoading(false); return true;
    }).catch((error: unknown) => {
      if ((controller.signal.aborted && !timedOut) || !active.current) return false;
      setOperators(null); setOperatorLoading(false); setBlocked(true);
      if (!auth(error)) setOperatorError('Eligible operators could not be loaded. Reload accounts to try again.');
      return false;
    }).finally(() => window.clearTimeout(timer));
  }, [client, auth]);
  function clearCredential() { ++generation.current; if (credentialField.current) credentialField.current.value = ''; setCredential(null); setCopyMessage(''); }
  function clearPasswords() {
    for (const form of [createForm.current, actionForm.current]) { const field = form?.elements.namedItem('currentPassword'); if (field instanceof HTMLInputElement) field.value = ''; }
  }
  useEffect(() => {
    active.current = true; void loadAccounts(0); void loadOperators(0, '');
    const clear = () => { clearPasswords(); clearCredential(); actionDialog.current?.close(); credentialDialog.current?.close(); };
    window.addEventListener('pagehide', clear);
    return () => { active.current = false; accountRead.current?.abort(); operatorRead.current?.abort(); mutation.current?.abort(); window.removeEventListener('pagehide', clear); };
  }, [loadAccounts, loadOperators]);
  useEffect(() => { if (action) { actionDialog.current?.showModal(); actionForm.current?.querySelector<HTMLInputElement>('input')?.focus(); } }, [action]);
  useEffect(() => { if (credential) credentialDialog.current?.showModal(); }, [credential]);
  function reload() {
    clearCredential(); credentialDialog.current?.close(); clearPasswords(); setAction(null); actionDialog.current?.close();
    setLoading(true); setOperatorLoading(true); setDirectory(null); setOperators(null); setSelected(''); setMessage(''); setOperatorError(''); setBlocked(true);
    void Promise.all([loadAccounts(directory?.page ?? 0), loadOperators(0, appliedSearch)]).then(results => { if (active.current && results.every(Boolean)) setBlocked(false); });
  }
  function searchOperators(number: number, query: string) {
    setSelected(''); setOperators(null); setOperatorLoading(true); setOperatorError(''); setAppliedSearch(query); void loadOperators(number, query);
  }
  async function submit(kind: 'create' | 'enabled' | 'reset', form: HTMLFormElement) {
    if (write.current || blocked || loading || operatorLoading || !directory || !operators) return;
    const fields = new FormData(form); const password = String(fields.get('currentPassword') ?? '');
    if (!password || (kind === 'create' && !selected) || (kind !== 'create' && !action)) return;
    write.current = true; setBusy(true); onBusyChange(true); clearPasswords(); clearCredential(); setMessage(''); setMessageKind('error');
    const controller = new AbortController(); mutation.current = controller; const timer = window.setTimeout(() => controller.abort(), 15000);
    try {
      let result: Credential | undefined;
      if (kind === 'create') result = await client.create(String(fields.get('username')), Number(selected), password, controller.signal);
      else if (kind === 'reset') result = await client.reset(action!.account.id, password, controller.signal);
      else await client.enabled(action!.account.id, !action!.account.enabled, password, controller.signal);
      if (!active.current) return;
      actionDialog.current?.close(); setAction(null);
      if (result) { setCredential(result); setCopyMessage(''); }
      if (kind === 'create') createForm.current?.reset();
      setMessageKind('success');
      setMessage(kind === 'create' ? 'Driver account created. Share the temporary password securely.' : kind === 'reset' ? 'Password reset. Previous sessions have been revoked.' : 'Account access updated. Previous sessions cannot be reused.');
      setLoading(true); setOperatorLoading(true); setSelected(''); setBlocked(true);
      const results = await Promise.all([loadAccounts(kind === 'create' ? 0 : directory.page, kind === 'create'), loadOperators(0, appliedSearch)]);
      if (active.current && results.every(Boolean)) setBlocked(false);
    } catch (error) {
      if (!active.current) return;
      actionDialog.current?.close(); setAction(null); setDirectory(null); setOperators(null); setSelected(''); setBlocked(true); setMessageKind('error');
      if (!auth(error)) setMessage(error instanceof ApiError && error.status === 400 ? 'Check the username, selected operator and required fields. Reload accounts before trying again.'
        : error instanceof ApiError && error.status === 403 ? 'Your administrator password was not accepted, or access has changed. Reload accounts before trying again.'
        : error instanceof ApiError && error.status === 404 ? 'This account or operator is no longer available. Reload accounts.'
        : error instanceof ApiError && error.status === 409 ? 'This change conflicts with an existing account. Usernames and operator links must be unique. Reload accounts.'
        : 'The outcome could not be confirmed. Reload accounts before another action. If creation succeeded without a received password, use Reset password to issue new credentials.');
    } finally { window.clearTimeout(timer); write.current = false; onBusyChange(false); if (active.current) setBusy(false); }
  }
  async function copy() {
    if (!credential) return; const version = generation.current;
    try { await navigator.clipboard.writeText(credential.temporaryPassword); if (active.current && version === generation.current) setCopyMessage('Copied. Share it securely with the intended driver.'); }
    catch { if (active.current && version === generation.current) setCopyMessage('Clipboard unavailable. Select and copy the password manually before closing.'); }
  }
  const unavailable = busy || blocked || loading || operatorLoading || !directory || !operators;
  const actionLabel = action?.kind === 'reset' ? 'Reset password' : action?.account.enabled ? 'Disable' : 'Enable';
  return <section className="accounts-page">
    <div className="accounts-heading"><div><p className="eyebrow">Administrator workspace</p><h1>Accounts</h1></div><button className="button secondary" disabled={busy || loading || operatorLoading} onClick={reload}>Reload accounts</button></div>
    {message && <p role={messageKind === 'error' ? 'alert' : 'status'} className={messageKind === 'error' ? 'error-message' : 'success-message'}>{message}</p>}
    <section className="accounts-panel" aria-labelledby="directory-title"><h2 id="directory-title">Account directory</h2>
      {loading ? <p role="status">Loading accounts…</p> : directory ? <>
        <p id="table-scroll-help" className="table-scroll-hint">Scroll across the table to see all account details and actions.</p><div className="accounts-table" role="region" aria-label="Account directory table" aria-describedby="table-scroll-help" tabIndex={0}><table><caption className="sr-only">Accounts and driver access controls</caption><thead><tr><th>Username</th><th>Role</th><th>Operator</th><th>Access</th><th>Password setup</th><th>Actions</th></tr></thead>
          <tbody>{directory.items.map(account => <tr key={account.id}><th scope="row">{account.username}</th><td>{account.role}</td><td>{account.operatorId === null ? '—' : `#${account.operatorId}`}</td><td>{account.enabled ? 'Enabled' : 'Disabled'}</td><td>{account.passwordChangeRequired ? 'Required' : 'Ready'}</td><td>{account.role === 'ADMIN' ? 'Administrator account' : <div className="account-row-actions">{(['enabled','reset'] as const).map(kind => <button key={kind} className="text-button" disabled={Boolean(unavailable)} onClick={event => { actionFocus.current = event.currentTarget; clearPasswords(); setAction({ account, kind }); }}>{kind === 'reset' ? 'Reset password' : account.enabled ? 'Disable' : 'Enable'}</button>)}</div>}</td></tr>)}</tbody></table></div>
        {directory.items.length === 0 && <p>No accounts found.</p>}
        <Pagination page={directory} label="accounts" disabled={busy || loading} change={number => { setDirectory(null); setLoading(true); void loadAccounts(number); }} />
      </> : <p>Account directory unavailable. Use Reload accounts to try again.</p>}
    </section>
    <section className="accounts-panel" aria-labelledby="create-title"><h2 id="create-title">Create driver account</h2><p>Link an existing operator and issue a temporary password for their first sign-in.</p>
      <form className="operator-search" onSubmit={event => { event.preventDefault(); searchOperators(0, search); }}><label htmlFor="operator-search">Search eligible operators</label><div className="search-controls"><input id="operator-search" maxLength={100} value={search} disabled={busy} onChange={event => setSearch(event.target.value)} /><button className="button secondary" disabled={busy}>Search operators</button></div></form>
      {operatorLoading ? <p role="status">Loading eligible operators…</p> : operatorError ? <p role="alert">{operatorError}</p> : operators && <><p>{operators.totalElements ? `${operators.totalElements} eligible operators. Linked operators, including disabled accounts, are excluded.` : 'No eligible operators match. Create an operator in scheduling or change your search.'}</p><Pagination page={operators} label="operators" disabled={busy || operatorLoading} change={number => searchOperators(number, appliedSearch)} /></>}
      <form ref={createForm} onSubmit={event => { event.preventDefault(); void submit('create', event.currentTarget); }} className="create-account">
        <label htmlFor="account-username">Driver username</label><input id="account-username" name="username" required minLength={3} maxLength={64} pattern="[a-z0-9][a-z0-9._\-]{2,63}" autoComplete="off" disabled={busy} />
        <label htmlFor="account-operator">Eligible operator</label><select id="account-operator" required value={selected} disabled={busy || operatorLoading || !operators?.items.length} onChange={event => setSelected(event.target.value)}><option value="">Select an eligible operator</option>{operators?.items.map(operator => <option key={operator.operatorId} value={operator.operatorId}>{operator.name} (#{operator.operatorId})</option>)}</select>
        <label htmlFor="create-current-password">Your administrator password</label><input id="create-current-password" name="currentPassword" type="password" autoComplete="off" required disabled={busy} />
        <button className="button" disabled={Boolean(unavailable) || !selected}>Create driver account</button>
      </form>
    </section>
    <dialog ref={actionDialog} className="account-dialog" aria-labelledby="action-title" onCancel={event => { if (write.current) event.preventDefault(); else { clearPasswords(); setAction(null); } }} onClose={() => { if (!actionDialog.current?.open) { clearPasswords(); setAction(null); } }}>
      <h2 id="action-title">{actionLabel} {action?.account.username}</h2><p>{action?.kind === 'reset' ? 'Revoke existing sessions and issue a temporary password. A disabled account remains disabled.' : action?.account.enabled ? 'Revoke existing sessions and block new sign-ins.' : 'The driver must sign in again. An expired temporary password still needs a reset.'}</p>
      <form ref={actionForm} onSubmit={event => { event.preventDefault(); if (action) void submit(action.kind, event.currentTarget); }}><label htmlFor="action-current-password">Your administrator password</label><input id="action-current-password" name="currentPassword" type="password" required autoComplete="off" disabled={busy} /><div className="dialog-actions"><button className="button" disabled={Boolean(unavailable)}>Confirm {actionLabel?.toLowerCase()}</button><button className="button secondary" type="button" disabled={busy} onClick={() => { clearPasswords(); actionDialog.current?.close(); setAction(null); actionFocus.current?.focus(); }}>Cancel action</button></div></form>
    </dialog>
    <dialog ref={credentialDialog} className="account-dialog" aria-labelledby="credential-title" onCancel={clearCredential} onClose={() => { if (!credentialDialog.current?.open) clearCredential(); }}>
      <h2 id="credential-title">Temporary driver password</h2><p>For {credential?.account.username}. Share securely; this password cannot be retrieved after closing.</p><p>{credential && `Expires ${new Date(credential.expiresAt).toLocaleString()} (your local time).`}</p>
      <label htmlFor="temporary-driver-password">Temporary password</label><input ref={credentialField} id="temporary-driver-password" readOnly value={credential?.temporaryPassword ?? ''} autoComplete="off" spellCheck={false} />
      <p role="status">{copyMessage}</p><div className="dialog-actions"><button className="button secondary" onClick={() => { void copy(); }}>Copy password</button><button className="button" autoFocus onClick={() => { clearCredential(); credentialDialog.current?.close(); }}>Done</button></div>
    </dialog>
  </section>;
}
function Pagination<T>({ page, label, disabled, change }: { page: Page<T>; label: string; disabled: boolean; change: (number: number) => void }) {
  return <div className="account-pagination" aria-label={`${label} pagination`}><button className="button secondary" disabled={disabled || page.page <= 0} onClick={() => change(page.page - 1)}>Previous {label}</button><span>{page.totalElements ? `Page ${page.page + 1} of ${page.totalPages} · ${page.totalElements} ${label}` : `0 ${label}`}</span><button className="button secondary" disabled={disabled || page.page + 1 >= page.totalPages} onClick={() => change(page.page + 1)}>Next {label}</button></div>;
}
