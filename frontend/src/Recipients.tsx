import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from './api';
import { recipientsApi } from './recipients-api';
import type { RecipientInput, RecipientPage, RecipientVersion, RecipientsClient } from './recipients-api';
const blank: RecipientInput = { firstName: '', lastName: '', address: '', contactNumber: '', priorityLevel: 'MEDIUM' };
export function Recipients({ client = recipientsApi, onAuthError, onBusyChange }: { client?: RecipientsClient; onAuthError: (error: ApiError) => void; onBusyChange: (busy: boolean) => void }) {
  const [directory, setDirectory] = useState<RecipientPage | null>(null);
  const [search, setSearch] = useState(''); const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true); const [reading, setReading] = useState(false);
  const [error, setError] = useState(''); const [message, setMessage] = useState('');
  const [editor, setEditor] = useState<'create' | 'edit' | null>(null);
  const [version, setVersion] = useState<RecipientVersion | null>(null);
  const [input, setInput] = useState<RecipientInput>({ ...blank });
  const [pending, setPending] = useState(false); const [blocked, setBlocked] = useState(false);
  const alive = useRef(true); const busy = useRef(false); const generation = useRef(0);
  const reads = useRef<AbortController | null>(null); const writes = useRef<AbortController | null>(null);
  const listHeading = useRef<HTMLHeadingElement>(null); const formHeading = useRef<HTMLHeadingElement>(null);
  const auth = useCallback((e: unknown) => { if (e instanceof ApiError && (e.status === 401 || e.passwordChangeRequired)) { onAuthError(e); return true; } return false; }, [onAuthError]);
  const load = useCallback(async (page: number, term: string) => {
    const sequence = ++generation.current; reads.current?.abort(); const controller = new AbortController(); reads.current = controller;
    const timer = window.setTimeout(() => controller.abort(), 30000);
    try {
      let result = await client.directory(page, term, controller.signal);
      if (page > 0 && result.recipients.length === 0 && !controller.signal.aborted) result = await client.directory(0, term, controller.signal);
      if (!alive.current || sequence !== generation.current || controller.signal.aborted) return;
      setDirectory(result); setLoading(false);
    } catch (e) { if (alive.current && sequence === generation.current) { setLoading(false); if (!auth(e)) setError('Recipients could not be loaded. Please reload the directory.'); } }
    finally { window.clearTimeout(timer); }
  }, [client, auth]);
  useEffect(() => { alive.current = true; void Promise.resolve().then(() => { if (alive.current) return load(0, ''); }); return () => { alive.current = false; reads.current?.abort(); writes.current?.abort(); }; }, [load]);
  useEffect(() => { if (editor) formHeading.current?.focus(); }, [editor, reading]);
  function reload(page = 0, term = query) { if (busy.current) return; setError(''); setLoading(true); setDirectory(null); setEditor(null); setVersion(null); setBlocked(false); setReading(false); void load(page, term); }
  function create() { ++generation.current; reads.current?.abort(); setReading(false); setLoading(false); setVersion(null); setInput({ ...blank }); setEditor('create'); setError(''); setMessage(''); setBlocked(false); }
  async function edit(id: number) {
    const sequence = ++generation.current; reads.current?.abort(); const controller = new AbortController(); reads.current = controller;
    setReading(true); setEditor(null); setVersion(null); setError(''); setMessage(''); setBlocked(false);
    const timer = window.setTimeout(() => controller.abort(), 30000);
    try { const result = await client.read(id, controller.signal); if (!alive.current || sequence !== generation.current || controller.signal.aborted) return;
      setVersion(result); setInput({ ...result.recipient }); setEditor('edit');
    } catch (e) { if (alive.current && sequence === generation.current && !auth(e)) setError('Recipient details could not be loaded. Reload the directory and try again.'); }
    finally { window.clearTimeout(timer); if (alive.current && sequence === generation.current) setReading(false); }
  }
  async function save() {
    if (busy.current || blocked || !editor || (editor === 'edit' && !version)) return;
    if (![input.firstName, input.lastName, input.address].every(v => v.trim())) { setError('Enter a first name, last name and address.'); return; }
    busy.current = true; setPending(true); onBusyChange(true); setError(''); setMessage('');
    const controller = new AbortController(); writes.current = controller; const timer = window.setTimeout(() => controller.abort(), 15000);
    try {
      const result = editor === 'edit' && version ? await client.update(version.recipient.recipientId, version.etag, input, controller.signal) : await client.create(input, controller.signal);
      if (!alive.current) return; if (controller.signal.aborted) throw new ApiError(0);
      setEditor(null); setVersion(null); setInput({ ...blank }); setMessage(`Recipient ${result.recipient.firstName} ${result.recipient.lastName} saved.`);
      setLoading(true); setDirectory(null); void load(0, query); listHeading.current?.focus();
    } catch (e) {
      if (!alive.current || auth(e)) return;
      const status = e instanceof ApiError ? e.status : 0;
      if (status === 400) setError('Check the field lengths and required details, then submit again.');
      else if (status === 503) setError('Address lookup is unavailable or could not locate this address. No change was saved. Check the address or try again later.');
      else { setBlocked(true); setError(status === 409 || status === 412 ? 'This recipient changed while you were editing. Reload the directory and reopen the recipient to review the latest details.' : status === 404 ? 'This recipient is no longer available. Reload the directory.' : status === 403 ? 'You do not have permission to save. Reload the directory before another attempt.' : 'The save outcome could not be confirmed. Reload and inspect the directory before creating or editing again.'); }
    } finally { window.clearTimeout(timer); busy.current = false; onBusyChange(false); if (alive.current) setPending(false); }
  }
  const locked = pending || reading;
  return <><section className="page-intro"><p className="eyebrow">Recipient management</p><h1>People behind every delivery.</h1><p className="intro-text">Find recipients and keep their delivery details up to date.</p></section>
    {error && <p className="error-message" role="alert">{error}</p>}{message && <p role="status">{message}</p>}
    <section className="recipient-panel" aria-labelledby="recipient-directory"><div className="recipient-toolbar"><h2 id="recipient-directory" ref={listHeading} tabIndex={-1}>Recipient directory</h2><button className="button" disabled={locked || loading || blocked} onClick={create}>New recipient</button></div>
      <form className="recipient-toolbar" onSubmit={e => { e.preventDefault(); setQuery(search.trim()); reload(0, search.trim()); }}><label>Search name or address<input value={search} maxLength={100} disabled={pending} onChange={e => setSearch(e.target.value)} /></label><button className="button secondary" disabled={pending}>Search recipients</button><button type="button" className="text-button" disabled={pending} onClick={() => reload()}>Reload directory</button></form>
      {loading ? <p role="status">Loading recipients…</p> : directory && <><p>{directory.totalElements} {directory.totalElements === 1 ? 'recipient' : 'recipients'} found.</p>{directory.recipients.length === 0 ? <p>No recipients match your search.</p> : <><p id="recipient-scroll-hint" className="muted">On smaller screens, scroll the table to see all details.</p><div className="recipient-table" role="region" aria-label="Recipients" aria-describedby="recipient-scroll-hint" tabIndex={0}><table><thead><tr><th scope="col">Name</th><th scope="col">Address</th><th scope="col">Contact</th><th scope="col">Priority</th><th scope="col">Action</th></tr></thead><tbody>{directory.recipients.map(r => <tr key={r.recipientId}><td>{r.firstName} {r.lastName}</td><td>{r.address}</td><td>{r.contactNumber || 'Not provided'}</td><td>{r.priorityLevel}</td><td><button className="button secondary" disabled={locked || blocked} onClick={() => { void edit(r.recipientId); }} aria-label={`Edit ${r.firstName} ${r.lastName}`}>Edit</button></td></tr>)}</tbody></table></div></>}
      <div className="recipient-toolbar"><button className="button secondary" disabled={locked || directory.page === 0} onClick={() => reload(directory.page - 1)}>Previous recipients</button><span>Page {directory.totalPages ? directory.page + 1 : 0} of {directory.totalPages}</span><button className="button secondary" disabled={locked || directory.page + 1 >= directory.totalPages || directory.page >= 10000} onClick={() => reload(directory.page + 1)}>Next recipients</button></div></>}
    </section>{reading && <p role="status">Loading latest recipient details…</p>}
    {editor && <section className="recipient-panel" aria-labelledby="recipient-editor"><h2 id="recipient-editor" ref={formHeading} tabIndex={-1}>{editor === 'create' ? 'Create recipient' : 'Edit recipient'}</h2>
      {editor === 'edit' && <p className="recipient-notice">Address changes affect the recipient used by existing deliveries. Existing route travel estimates are not recalculated.</p>}
      <form onSubmit={e => { e.preventDefault(); void save(); }}><fieldset disabled={pending || blocked}><legend>Recipient details</legend><div className="recipient-fields">
      {(['firstName', 'lastName', 'address', 'contactNumber'] as const).map(field => <div key={field}><label htmlFor={`recipient-${field}`}>{({ firstName: 'First name', lastName: 'Last name', address: 'Address', contactNumber: 'Contact number (optional)' })[field]}</label>{field === 'address' ? <textarea id={`recipient-${field}`} required maxLength={1000} value={input.address} onChange={e => setInput({ ...input, address: e.target.value })} /> : <input id={`recipient-${field}`} type={field === 'contactNumber' ? 'tel' : 'text'} required={field !== 'contactNumber'} maxLength={field === 'contactNumber' ? 20 : 50} value={input[field] ?? ''} onChange={e => setInput({ ...input, [field]: e.target.value })} />}</div>)}
      <div><label htmlFor="recipient-priority">Priority</label><select id="recipient-priority" value={input.priorityLevel} onChange={e => setInput({ ...input, priorityLevel: e.target.value as RecipientInput['priorityLevel'] })}><option value="HIGH">High</option><option value="MEDIUM">Medium</option><option value="LOW">Low</option></select></div></div></fieldset>
      {pending && <p role="status">Saving recipient… Address lookup may be required.</p>}<div className="recipient-toolbar"><button className="button" disabled={pending || blocked}>Save recipient</button><button type="button" className="button secondary" disabled={pending} onClick={() => { setEditor(null); setVersion(null); setInput({ ...blank }); listHeading.current?.focus(); }}>Cancel</button></div></form></section>}
  </>;
}
