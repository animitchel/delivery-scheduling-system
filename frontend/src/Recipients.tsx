import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from './api';
import { recipientsApi } from './recipients-api';
import './recipients.css';
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
  const [submitted, setSubmitted] = useState(false);
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
      setDirectory(result); setLoading(false); setBlocked(false);
    } catch (e) { if (alive.current && sequence === generation.current) { setLoading(false); if (!auth(e)) setError('Recipients could not be loaded. Please reload the directory.'); } }
    finally { window.clearTimeout(timer); }
  }, [client, auth]);
  useEffect(() => { alive.current = true; void Promise.resolve().then(() => { if (alive.current) return load(0, ''); }); return () => { alive.current = false; reads.current?.abort(); writes.current?.abort(); }; }, [load]);
  useEffect(() => { if (editor) formHeading.current?.focus(); }, [editor, reading]);
  function reload(page = 0, term = query) { if (busy.current) return; setError(''); setLoading(true); setDirectory(null); setEditor(null); setVersion(null); setReading(false); void load(page, term); }
  function create() { if (busy.current) return; setSubmitted(false); ++generation.current; reads.current?.abort(); setReading(false); setLoading(false); setVersion(null); setInput({ ...blank }); setEditor('create'); setError(''); setMessage(''); setBlocked(false); }
  async function edit(id: number) {
    if (busy.current) return; setSubmitted(false);
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
    setSubmitted(true);
    const missing = (['firstName', 'lastName', 'address'] as const).find(field => !input[field].trim());
    if (missing) { setError('Enter a first name, last name and address. Check the highlighted fields.'); document.getElementById(`recipient-${missing}`)?.focus(); return; }
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
  const invalid = (field: 'firstName' | 'lastName' | 'address') => submitted && !input[field].trim();
  const names = { firstName: 'First name', lastName: 'Last name', address: 'Address' };
  function cancel() { setEditor(null); setVersion(null); setInput({ ...blank }); setSubmitted(false); if (!blocked) setError(''); listHeading.current?.focus(); }
  function clearSearch() { if (busy.current) return; setSearch(''); setQuery(''); reload(0, ''); }
  function field(name: 'firstName' | 'lastName' | 'address') {
    const props = { id: `recipient-${name}`, required: true, maxLength: name === 'address' ? 1000 : 50,
      value: input[name], 'aria-invalid': invalid(name),
      'aria-describedby': [name === 'address' ? 'recipient-address-help' : '', invalid(name) ? `recipient-${name}-error` : ''].filter(Boolean).join(' ') || undefined,
      onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setInput({ ...input, [name]: event.target.value }) };
    return <div className={name === 'address' ? 'recipient-address-field' : undefined}>
      <div className="recipient-field-label"><label htmlFor={props.id}>{names[name]}</label><span className="recipient-required">Required</span></div>
      {name === 'address' ? <textarea {...props} rows={3} /> : <input {...props} type="text" />}
      {name === 'address' && <p id="recipient-address-help" className="recipient-field-help">Include the street, town or city, and postcode so the address can be located.</p>}
      {invalid(name) && <p id={`recipient-${name}-error`} className="recipient-field-error">Enter {name === 'address' ? 'an address' : `a ${names[name].toLowerCase()}`}.</p>}
    </div>;
  }
  return <div className="recipients-page">
    <section className="page-intro recipient-page-heading"><div><p className="eyebrow">Recipient management</p><h1>Recipients</h1><p className="intro-text">Keep contact and delivery details ready for your next assignment.</p></div>
      <button className="button" disabled={locked || loading || blocked || editor !== null} onClick={create}><span aria-hidden="true">＋</span>Add recipient</button>
    </section>
    {error && <div className="recipient-feedback"><p className="error-message" role="alert">{error}</p>{blocked && <button className="button secondary" disabled={pending} onClick={() => reload()}>Reload and review recipients</button>}</div>}
    {message && <p className="success-message" role="status">{message}</p>}
    {reading && <p className="recipient-read-status" role="status">Loading latest recipient details…</p>}
    {editor && <section className="recipient-panel recipient-editor-panel" aria-labelledby="recipient-editor">
      <div className="recipient-section-heading"><div><p className="eyebrow">{editor === 'create' ? 'New contact' : 'Update details'}</p><h2 id="recipient-editor" ref={formHeading} tabIndex={-1}>{editor === 'create' ? 'Create recipient' : 'Edit recipient'}</h2><p>Fields marked Required must be completed.</p></div></div>
      {editor === 'edit' && <p className="recipient-notice">Address changes affect the recipient used by existing deliveries. Existing route travel estimates are not recalculated.</p>}
      <form noValidate autoComplete="off" onSubmit={event => { event.preventDefault(); void save(); }}>
        <fieldset disabled={pending || blocked}><legend>Contact details</legend><div className="recipient-fields">
          {field('firstName')}{field('lastName')}
          <div><label htmlFor="recipient-contactNumber">Contact number (optional)</label><input id="recipient-contactNumber" type="tel" maxLength={20} value={input.contactNumber ?? ''} aria-describedby="recipient-contact-help" onChange={event => setInput({ ...input, contactNumber: event.target.value })} /><p id="recipient-contact-help" className="recipient-field-help">Add a number the team can use to reach this recipient.</p></div>
        </div></fieldset>
        <fieldset disabled={pending || blocked}><legend>Delivery details</legend><div className="recipient-fields">
          {field('address')}
          <div><label htmlFor="recipient-priority">Priority</label><select id="recipient-priority" value={input.priorityLevel} onChange={event => setInput({ ...input, priorityLevel: event.target.value as RecipientInput['priorityLevel'] })}><option value="HIGH">High</option><option value="MEDIUM">Medium</option><option value="LOW">Low</option></select></div>
        </div></fieldset>
        {pending && <p role="status">Saving recipient… Address lookup may be required.</p>}
        <div className="recipient-editor-actions"><button className="button" disabled={pending || blocked}>{pending ? 'Saving…' : 'Save recipient'}</button><button type="button" className="button secondary" disabled={pending} onClick={cancel}>Cancel</button></div>
      </form>
    </section>}
    <section className="recipient-panel recipient-directory-panel" aria-labelledby="recipient-directory">
      <div className="recipient-section-heading"><div><h2 id="recipient-directory" ref={listHeading} tabIndex={-1}>Recipient directory</h2><p>Search by name or address to find the right contact.</p></div><button type="button" className="text-button" disabled={pending} onClick={() => reload()}>Reload directory</button></div>
      <form className="recipient-search-toolbar" role="search" aria-label="Find recipients" onSubmit={event => { event.preventDefault(); setQuery(search.trim()); reload(0, search.trim()); }}>
        <label htmlFor="recipient-search">Search name or address<input id="recipient-search" type="search" value={search} maxLength={100} disabled={pending} placeholder="Enter a name or address" onChange={event => setSearch(event.target.value)} /></label>
        <button className="button secondary" disabled={pending}>Search recipients</button>
        {query && <button type="button" className="text-button" disabled={pending} onClick={clearSearch}>Clear search</button>}
      </form>
      {loading ? <div className="recipient-loading"><p role="status">Loading recipients…</p><div className="recipient-skeleton" aria-hidden="true"><span /><span /><span /></div></div> : directory && <>
        <p className="recipient-result-count" role="status">{directory.totalElements} {directory.totalElements === 1 ? 'recipient' : 'recipients'} found.{query && <> Results for “{query}”.</>}</p>
        {directory.recipients.length === 0 ? <div className="recipient-empty"><span className="recipient-empty-symbol" aria-hidden="true">＋</span><h3>{query ? 'No recipients match your search.' : 'Your recipient directory starts here.'}</h3><p>{query ? 'Try a different name or address, or clear the search to see all recipients.' : 'Add your first recipient with their contact details and delivery address. Then you can prepare deliveries for them.'}</p></div> : <>
          <div className="recipient-table recipient-directory-results"><table role="table" aria-label="Recipients"><thead role="rowgroup"><tr role="row"><th scope="col">Name</th><th scope="col">Address</th><th scope="col">Contact</th><th scope="col">Priority</th><th scope="col">Action</th></tr></thead><tbody role="rowgroup">{directory.recipients.map(recipient => <tr key={recipient.recipientId} role="row">
            <th scope="row"><span className="recipient-name">{recipient.firstName} {recipient.lastName}</span><span className="recipient-record-id">Recipient #{recipient.recipientId}</span></th>
            <td role="cell"><span className="recipient-mobile-label" aria-hidden="true">Address</span><span>{recipient.address}</span></td>
            <td role="cell"><span className="recipient-mobile-label" aria-hidden="true">Contact</span><span>{recipient.contactNumber || 'Not provided'}</span></td>
            <td role="cell"><span className="recipient-mobile-label" aria-hidden="true">Priority</span><span className={`recipient-priority recipient-priority-${recipient.priorityLevel.toLowerCase()}`}>{({ HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low' })[recipient.priorityLevel]}</span></td>
            <td role="cell" className="recipient-record-action"><button className="button secondary" disabled={locked || blocked || editor !== null} onClick={() => { void edit(recipient.recipientId); }} aria-label={`Edit ${recipient.firstName} ${recipient.lastName}`}>Edit<span className="recipient-mobile-label" aria-hidden="true"> recipient</span></button></td>
          </tr>)}</tbody></table></div>
          <nav className="recipient-pagination" aria-label="Recipient pages"><button className="button secondary" disabled={locked || directory.page === 0} onClick={() => reload(directory.page - 1)}>Previous recipients</button><span>Page {directory.page + 1} of {directory.totalPages}</span><button className="button secondary" disabled={locked || directory.page + 1 >= directory.totalPages || directory.page >= 10000} onClick={() => reload(directory.page + 1)}>Next recipients</button></nav>
        </>}
      </>}
    </section>
  </div>;
}
