import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from './api';
import { deliveriesApi, deliveryStatuses, inputError, validDate } from './deliveries-api';
import type { DeliveriesClient, DeliveryDetails, DeliveryFilters, DeliveryInput, DeliveryPage } from './deliveries-api';
import './deliveries.css';
import { recipientsApi } from './recipients-api';
import type { Recipient, RecipientPage, RecipientsClient } from './recipients-api';

type Draft = Omit<DeliveryInput, 'recipientId'>;
type Lane = 'recipients' | 'selection' | 'directory' | 'details';
interface PendingRead { sequence: number; controller: AbortController | null }
interface Uncertain { recipientId: number; scheduledDate: string; name: string }
const blank: Draft = { scheduledDate: '', startTimeWindow: '', endTimeWindow: '', priorityLevel: 'MEDIUM', specialInstructions: '' };
const all: DeliveryFilters = { scheduledDate: '', status: '', recipientId: null };
const label = (value: string) => value.replaceAll('_', ' ').toLowerCase();
export function Deliveries({ client = deliveriesApi, recipientClient = recipientsApi, onAuthError, onBusyChange }: {
  client?: DeliveriesClient; recipientClient?: RecipientsClient;
  onAuthError: (error: ApiError) => void; onBusyChange: (busy: boolean) => void;
}) {
  const [recipients, setRecipients] = useState<RecipientPage | null>(null);
  const [recipientLoading, setRecipientLoading] = useState(true);
  const [recipientError, setRecipientError] = useState('');
  const [search, setSearch] = useState(''); const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<Recipient | null>(null);
  const [selecting, setSelecting] = useState(false);
  const [draft, setDraft] = useState<Draft>({ ...blank });
  const [submitted, setSubmitted] = useState(false);
  const recipientHeading = useRef<HTMLHeadingElement>(null);
  const recipientSearchInput = useRef<HTMLInputElement>(null);
  const focusRecipientSearch = useRef(false);
  const [pending, setPending] = useState(false); const [permissionBlocked, setPermissionBlocked] = useState(false);
  const [saveError, setSaveError] = useState(''); const [message, setMessage] = useState('');
  const [uncertain, setUncertain] = useState<Uncertain | null>(null);
  const [reviewReady, setReviewReady] = useState(false);
  const [directory, setDirectory] = useState<DeliveryPage | null>(null);
  const [directoryLoading, setDirectoryLoading] = useState(true); const [directoryError, setDirectoryError] = useState('');
  const [filters, setFilters] = useState<DeliveryFilters>({ ...all });
  const [filterDate, setFilterDate] = useState(''); const [filterStatus, setFilterStatus] = useState<DeliveryFilters['status']>('');
  const [filterName, setFilterName] = useState('');
  const [details, setDetails] = useState<DeliveryDetails | null>(null);
  const [detailsLoading, setDetailsLoading] = useState(false); const [detailsError, setDetailsError] = useState('');
  const alive = useRef(true); const busy = useRef(false); const write = useRef<AbortController | null>(null);
  const reads = useRef<Record<Lane, PendingRead>>({ recipients: { sequence: 0, controller: null }, selection: { sequence: 0, controller: null }, directory: { sequence: 0, controller: null }, details: { sequence: 0, controller: null } });
  const createHeading = useRef<HTMLHeadingElement>(null); const detailsHeading = useRef<HTMLHeadingElement>(null);
  const directoryHeading = useRef<HTMLHeadingElement>(null);
  const auth = useCallback((error: unknown) => {
    if (error instanceof ApiError && (error.status === 401 || error.passwordChangeRequired)) { onAuthError(error); return true; }
    return false;
  }, [onAuthError]);
  const cancel = useCallback((lane: Lane) => { const current = reads.current[lane]; ++current.sequence; current.controller?.abort(); }, []);
  const run = useCallback(async <T,>(lane: Lane, operation: (signal: AbortSignal) => Promise<T>, success: (value: T) => void, failure: () => void) => {
    const current = reads.current[lane]; const sequence = ++current.sequence;
    current.controller?.abort(); const controller = new AbortController(); current.controller = controller;
    const timer = window.setTimeout(() => controller.abort(), 30000);
    try {
      const value = await operation(controller.signal);
      if (!alive.current || sequence !== current.sequence) return;
      if (controller.signal.aborted) throw new ApiError(0);
      success(value);
    } catch (error) { if (alive.current && sequence === current.sequence && !auth(error)) failure(); }
    finally { window.clearTimeout(timer); }
  }, [auth]);
  const loadRecipients = useCallback((page: number, term: string) => run('recipients', async signal => {
    let result = await recipientClient.directory(page, term, signal);
    if (page > 0 && result.recipients.length === 0 && !signal.aborted) result = await recipientClient.directory(0, term, signal);
    return result;
  }, result => { setRecipients(result); setRecipientLoading(false); }, () => { setRecipientError('Recipients could not be loaded. Reload or search again.'); setRecipientLoading(false); }), [run, recipientClient]);
  const loadDirectory = useCallback((page: number, criteria: DeliveryFilters, review: boolean) => run('directory', async signal => {
    let result = await client.directory(page, criteria, signal);
    if (page > 0 && result.deliveries.length === 0 && !signal.aborted) result = await client.directory(0, criteria, signal);
    return result;
  }, result => { setDirectory(result); setDirectoryLoading(false); if (review) setReviewReady(true); }, () => { setDirectoryError('Deliveries could not be loaded. Please reload the directory.'); setDirectoryLoading(false); setReviewReady(false); }), [run, client]);
  useEffect(() => {
    alive.current = true; const lanes = reads.current;
    void Promise.resolve().then(() => { if (alive.current) { void loadRecipients(0, ''); void loadDirectory(0, all, false); } });
    return () => { alive.current = false; for (const lane of Object.values(lanes)) lane.controller?.abort(); write.current?.abort(); };
  }, [loadDirectory, loadRecipients]);
  useEffect(() => { if (details) detailsHeading.current?.focus(); }, [details]);
  useEffect(() => {
    if (selected) createHeading.current?.focus();
    else if (focusRecipientSearch.current) {
      focusRecipientSearch.current = false;
      recipientSearchInput.current?.focus();
    }
  }, [selected]);
  function changeRecipient() {
    if (busy.current || uncertain || permissionBlocked) return;
    focusRecipientSearch.current = true;
    setSelected(null); setSubmitted(false); setSaveError(''); setMessage('');
  }
  function recipientSearch(page = 0, term = query) {
    if (busy.current || uncertain) return;
    cancel('selection'); setSelecting(false); setRecipientLoading(true); setRecipients(null); setRecipientError('');
    void loadRecipients(page, term);
  }
  function choose(id: number) {
    if (busy.current || uncertain) return;
    setSubmitted(false); setSelecting(true); setSelected(null); setRecipientError(''); setSaveError(''); setMessage('');
    void run('selection', signal => recipientClient.read(id, signal), result => {
      setSelected(result.recipient); setSelecting(false);
    }, () => { setSelecting(false); setRecipientError('The selected recipient could not be loaded. Search or reload before choosing again.'); });
  }
  function showDirectory(page = 0, criteria = filters, review = false) {
    if (busy.current) return;
    setDirectoryLoading(true); setDirectory(null); setDirectoryError(''); setReviewReady(false);
    cancel('details'); setDetails(null); setDetailsLoading(false); setDetailsError('');
    void loadDirectory(page, criteria, review);
  }
  function applyFilters(criteria: DeliveryFilters, name: string) {
    setFilters(criteria); setFilterName(name); setFilterDate(criteria.scheduledDate); setFilterStatus(criteria.status);
    showDirectory(0, criteria);
  }
  function reviewUncertain() {
    if (!uncertain || busy.current) return;
    const criteria: DeliveryFilters = { scheduledDate: uncertain.scheduledDate, recipientId: uncertain.recipientId, status: '' };
    setFilters(criteria); setFilterDate(criteria.scheduledDate); setFilterStatus(''); setFilterName(uncertain.name);
    showDirectory(0, criteria, true); directoryHeading.current?.focus();
  }
  function openDetails(id: number) {
    if (busy.current) return;
    setDetails(null); setDetailsLoading(true); setDetailsError('');
    void run('details', signal => client.read(id, signal), value => { setDetails(value); setDetailsLoading(false); }, () => { setDetailsLoading(false); setDetailsError('Delivery details could not be loaded. Reload the directory and try again.'); });
  }
  async function save() {
    if (busy.current || uncertain || permissionBlocked || selecting) return;
    const input: DeliveryInput = { ...draft, recipientId: selected?.recipientId ?? 0 };
    setSubmitted(true);
    const invalid = inputError(input); if (invalid) {
      setSaveError(invalid);
      if (!selected) recipientHeading.current?.focus();
      else document.getElementById(!validDate(draft.scheduledDate) ? 'delivery-date' : !draft.startTimeWindow ? 'delivery-start' : 'delivery-end')?.focus();
      return;
    }
    const context = { recipientId: input.recipientId, scheduledDate: input.scheduledDate, name: `${selected!.firstName} ${selected!.lastName}` };
    busy.current = true; setPending(true); onBusyChange(true); setSaveError(''); setMessage('');
    const controller = new AbortController(); write.current = controller;
    const timer = window.setTimeout(() => controller.abort(), 15000);
    try {
      const result = await client.create(input, controller.signal);
      if (!alive.current) return; if (controller.signal.aborted) throw new ApiError(0);
      setSubmitted(false); setMessage(`Delivery #${result.deliveryId} created.`); setSelected(null); setDraft({ ...blank });
      const criteria: DeliveryFilters = { recipientId: input.recipientId, scheduledDate: input.scheduledDate, status: '' };
      setFilters(criteria); setFilterDate(criteria.scheduledDate); setFilterStatus(''); setFilterName(context.name);
      setDirectory(null); setDirectoryLoading(true); setDirectoryError(''); void loadDirectory(0, criteria, false);
      cancel('details'); setDetails(result); setDetailsLoading(false); setDetailsError('');
    } catch (error) {
      if (!alive.current || auth(error)) return;
      const status = error instanceof ApiError ? error.status : 0;
      if (status === 400) setSaveError('Check the recipient, date, delivery window and instruction length, then submit again.');
      else if (status === 404 || status === 409) { setSelected(null); setSaveError('The delivery was not saved. Reload recipients and choose a current recipient before trying again.'); }
      else if (status === 403) { setPermissionBlocked(true); setSaveError('You do not have permission to create this delivery. Refresh your session before trying again.'); }
      else { setUncertain(context); setReviewReady(false); setSaveError('The save outcome could not be confirmed. Review possible saved deliveries before deciding on another submission.'); }
    } finally { window.clearTimeout(timer); busy.current = false; onBusyChange(false); if (alive.current) setPending(false); }
  }
  const frozen = pending || uncertain !== null;
  const dateError = submitted && !validDate(draft.scheduledDate) ? 'Enter a valid scheduled date between years 1000 and 9999.' : '';
  const windowError = submitted ? inputError({ ...draft, recipientId: 1, scheduledDate: '2000-01-01', priorityLevel: 'MEDIUM', specialInstructions: '' }) : null;
  const filtered = Boolean(filters.scheduledDate || filters.status || filters.recipientId !== null);
  function prepare() { if (busy.current || frozen || permissionBlocked) return; const target = selected ? createHeading.current : recipientHeading.current; target?.focus(); target?.scrollIntoView?.({ block: 'start' }); }
  function clearRecipientSearch() { if (busy.current || uncertain) return; setSearch(''); setQuery(''); recipientSearch(0, ''); }
  return <div className="deliveries-page">
    <section className="page-intro delivery-page-heading"><div><p className="eyebrow">Delivery preparation</p><h1>Deliveries</h1><p className="intro-text">Prepare upcoming work and keep every saved delivery in view.</p></div><button className="button" disabled={frozen || permissionBlocked} onClick={prepare}><span aria-hidden="true">＋</span>Prepare delivery</button></section>
    {message && <p className="success-message" role="status">{message}</p>}{saveError && <p className="error-message" role="alert">{saveError}</p>}
    {uncertain && <section className="recipient-panel delivery-panel delivery-recovery" aria-labelledby="save-review"><h2 id="save-review">Check before submitting again</h2>
      <p>Review deliveries for {uncertain.name} on {uncertain.scheduledDate}, across all statuses. Work may already have been assigned.</p>
      <p>An empty list or a matching delivery cannot prove which request saved it. Inspect details and additional pages before deciding whether another creation is needed.</p>
      <button className="button" disabled={pending} onClick={reviewUncertain}>Review possible saved deliveries</button>
      <button className="button secondary" disabled={!reviewReady || directoryLoading} onClick={() => { setUncertain(null); setReviewReady(false); setSaveError('Review completed. Another submission will create a new delivery.'); }}>I reviewed results; allow another submission</button>
    </section>}
    <section className="recipient-panel delivery-panel delivery-picker" aria-labelledby="choose-recipient"><div className="delivery-section-heading"><span className="delivery-step" aria-hidden="true">01</span><div><h2 id="choose-recipient" ref={recipientHeading} tabIndex={-1}>Choose a recipient</h2><p>Find the contact and confirm their current delivery address.</p></div></div>
      {selected && <div className="delivery-selected" role="group" aria-label="Selected delivery recipient"><div><strong>Selected recipient: {selected.firstName} {selected.lastName}</strong><p>{selected.address}</p><span>Recipient #{selected.recipientId}</span></div><button type="button" className="button secondary" disabled={frozen || permissionBlocked} onClick={changeRecipient}>Change recipient</button></div>}
      <div id="delivery-recipient-picker" hidden={selected !== null}>
      <form className="delivery-search-toolbar" role="search" aria-label="Find a delivery recipient" onSubmit={event => { event.preventDefault(); if (frozen) return; setQuery(search.trim()); recipientSearch(0, search.trim()); }}>
        <label htmlFor="delivery-recipient-search">Search recipient name or address<input id="delivery-recipient-search" ref={recipientSearchInput} type="search" maxLength={100} value={search} disabled={frozen} onChange={event => setSearch(event.target.value)} /></label>
        <button className="button secondary" disabled={frozen}>Search recipients</button><button type="button" className="text-button" disabled={frozen} onClick={() => recipientSearch()}>Reload recipients</button>{query && <button type="button" className="text-button" disabled={frozen} onClick={clearRecipientSearch}>Clear recipient search</button>}
      </form>
      {recipientError && <p role="alert" className="error-message">{recipientError}</p>}
      {recipientLoading ? <Loading text="Loading recipients…" /> : recipients && <>
        <p className="delivery-result-count">{recipients.totalElements} {recipients.totalElements === 1 ? 'recipient' : 'recipients'} found.{query && <> Results for “{query}”.</>}</p>
        {recipients.recipients.length ? <table className="delivery-recipient-results" role="table" aria-label="Recipient search results"><thead role="rowgroup"><tr role="row"><th scope="col" role="columnheader">Recipient</th><th scope="col" role="columnheader">Delivery address</th><th scope="col" role="columnheader">Action</th></tr></thead><tbody role="rowgroup">{recipients.recipients.map(recipient => <tr key={recipient.recipientId} role="row">
          <th scope="row" role="rowheader"><strong>{recipient.firstName} {recipient.lastName}</strong><small>Recipient #{recipient.recipientId}</small></th>
          <td role="cell" className="delivery-recipient-address">{recipient.address}</td>
          <td role="cell" className="delivery-recipient-action"><button className="button secondary" disabled={frozen || selecting} onClick={() => choose(recipient.recipientId)} aria-label={`Select ${recipient.firstName} ${recipient.lastName}, recipient ${recipient.recipientId}`}>Select</button></td>
        </tr>)}</tbody></table> : <Empty title={query ? 'No recipients match your search.' : 'Start with a recipient'} text={query ? 'Try another name or address, or clear the search to see all recipients.' : 'Add a recipient in Recipients, then reload this list to prepare their first delivery.'} />}
        {recipients.recipients.length > 0 && <Pagination page={recipients.page} total={recipients.totalPages} disabled={frozen || selecting} label="recipients" change={recipientSearch} />}
      </>}{selecting && <p role="status">Loading current recipient details…</p>}
      </div>
    </section>
    <section className="recipient-panel delivery-panel" aria-labelledby="delivery-create"><div className="delivery-section-heading"><span className="delivery-step" aria-hidden="true">02</span><div><h2 id="delivery-create" tabIndex={-1} ref={createHeading}>Create delivery</h2><p>Set the schedule, priority and instructions before saving.</p></div></div>
      {!selected && <p>Choose a recipient from the search results above.</p>}
      <p>New deliveries are pending and unassigned. Route generation assigns operators. Past dates remain available for overdue work.</p>
      <form noValidate onSubmit={event => { event.preventDefault(); void save(); }}>
        <fieldset disabled={frozen || permissionBlocked}><legend>Schedule</legend><div className="recipient-fields">
          <div><label htmlFor="delivery-date">Scheduled date</label><input id="delivery-date" type="date" required min="1000-01-01" max="9999-12-31" value={draft.scheduledDate} aria-invalid={Boolean(dateError)} aria-describedby={dateError ? 'delivery-date-error delivery-date-help' : 'delivery-date-help'} onChange={event => setDraft({ ...draft, scheduledDate: event.target.value })} /><p id="delivery-date-help" className="delivery-field-help">Past dates are allowed for overdue work.</p>{dateError && <p id="delivery-date-error" className="delivery-field-error">{dateError}</p>}</div>
          <div><label htmlFor="delivery-start">Start time</label><input id="delivery-start" type="time" step="1" required value={draft.startTimeWindow} aria-invalid={Boolean(windowError)} aria-describedby="delivery-window-help delivery-window-error" onChange={event => setDraft({ ...draft, startTimeWindow: event.target.value })} /></div>
          <div><label htmlFor="delivery-end">End time</label><input id="delivery-end" type="time" step="1" required value={draft.endTimeWindow} aria-invalid={Boolean(windowError)} aria-describedby="delivery-window-help delivery-window-error" onChange={event => setDraft({ ...draft, endTimeWindow: event.target.value })} /></div>
        </div><p id="delivery-window-help" className="delivery-field-help">The delivery window must end after it starts on the same day. Times support whole seconds.</p><p id="delivery-window-error" className="delivery-field-error">{windowError}</p></fieldset>
        <fieldset disabled={frozen || permissionBlocked}><legend>Priority and instructions</legend><div className="recipient-fields">
          <div><label htmlFor="delivery-priority">Priority</label><select id="delivery-priority" value={draft.priorityLevel} onChange={event => setDraft({ ...draft, priorityLevel: event.target.value as Draft['priorityLevel'] })}><option value="HIGH">High</option><option value="MEDIUM">Medium</option><option value="LOW">Low</option></select></div>
          <div className="delivery-instructions"><label htmlFor="delivery-instructions">Special instructions (optional)</label><textarea id="delivery-instructions" maxLength={255} rows={3} value={draft.specialInstructions ?? ''} aria-describedby="delivery-instructions-help delivery-instructions-count" onChange={event => setDraft({ ...draft, specialInstructions: event.target.value })} /><p id="delivery-instructions-help" className="delivery-field-help">Include access details or other information useful at the stop.</p><small id="delivery-instructions-count">{draft.specialInstructions?.length ?? 0}/255 characters</small></div>
        </div></fieldset>
        <div className="delivery-create-actions"><button className="button" disabled={frozen || permissionBlocked || selecting || !selected}>Create delivery</button><span>Saved as pending and unassigned.</span></div>
        {pending && <p role="status">Saving delivery…</p>}
      </form>
    </section>
    <section className="recipient-panel delivery-panel" aria-labelledby="delivery-directory"><div className="delivery-section-heading delivery-directory-heading"><div><h2 id="delivery-directory" tabIndex={-1} ref={directoryHeading}>Delivery directory</h2><p>Filter saved work and open a delivery for its current details.</p></div></div>
      <form className="delivery-filter-toolbar" onSubmit={event => { event.preventDefault(); if (frozen) return; if (filterDate && !validDate(filterDate)) { setDirectoryError('Enter a valid filter date.'); return; } applyFilters({ ...filters, scheduledDate: filterDate, status: filterStatus }, filterName); }}>
        <label htmlFor="delivery-filter-date">Filter scheduled date<input id="delivery-filter-date" type="date" min="1000-01-01" max="9999-12-31" value={filterDate} disabled={frozen} onChange={event => setFilterDate(event.target.value)} /></label>
        <label htmlFor="delivery-filter-status">Filter status<select id="delivery-filter-status" value={filterStatus} disabled={frozen} onChange={event => setFilterStatus(event.target.value as DeliveryFilters['status'])}><option value="">All statuses</option>{deliveryStatuses.map(status => <option value={status} key={status}>{label(status)}</option>)}</select></label>
        <button className="button secondary" disabled={frozen}>Apply filters</button><button type="button" className="text-button" disabled={frozen} onClick={() => applyFilters({ ...all }, '')}>Clear filters</button>
      </form>
      <div className="recipient-toolbar"><button className="button secondary" disabled={frozen || !selected} onClick={() => { if (selected) applyFilters({ ...filters, recipientId: selected.recipientId }, `${selected.firstName} ${selected.lastName}`); }}>Filter by selected recipient</button>
        <button className="text-button" disabled={pending} onClick={() => { if (uncertain) reviewUncertain(); else showDirectory(); }}>Reload deliveries</button></div>
      {filtered && <div className="delivery-filter-summary" aria-label="Applied delivery filters"><strong>Showing:</strong>{filters.scheduledDate && <span>Date: {filters.scheduledDate}</span>}{filters.status && <span>Status: {label(filters.status)}</span>}{filters.recipientId !== null && <span>Recipient filter: {filterName} (#{filters.recipientId})</span>}</div>}
      {directoryError && <p role="alert" className="error-message">{directoryError}</p>}
      {directoryLoading ? <Loading text="Loading deliveries…" /> : directory && <>
        <p className="delivery-result-count" role="status">{directory.totalElements} {directory.totalElements === 1 ? 'delivery' : 'deliveries'} found.</p>
        {directory.deliveries.length ? <><div className="recipient-table delivery-directory-results" role="region" aria-label="Deliveries" tabIndex={0}>
          <table role="table" aria-label="Delivery records"><thead role="rowgroup"><tr role="row"><th scope="col">Delivery</th><th scope="col">Recipient</th><th scope="col">Date and window</th><th scope="col">Priority</th><th scope="col">Status</th><th scope="col">Action</th></tr></thead><tbody role="rowgroup">{directory.deliveries.map(delivery => <tr key={delivery.deliveryId} role="row">
            <th scope="row">Delivery #{delivery.deliveryId}</th><td role="cell"><span className="delivery-mobile-label" aria-hidden="true">Recipient</span><span>{delivery.firstName} {delivery.lastName}</span></td><td role="cell"><span className="delivery-mobile-label" aria-hidden="true">Date and window</span><span>{delivery.scheduledDate ?? 'Date not provided'}<br />{delivery.startTimeWindow ?? 'Start not provided'} – {delivery.endTimeWindow ?? 'End not provided'}</span></td><td role="cell"><span className="delivery-mobile-label" aria-hidden="true">Priority</span><span className="delivery-priority">{label(delivery.priorityLevel)}</span></td><td role="cell"><span className="delivery-mobile-label" aria-hidden="true">Status</span><span className={`delivery-status delivery-status-${delivery.deliveryStatus.toLowerCase()}`}>{label(delivery.deliveryStatus)}</span></td>
            <td role="cell" className="delivery-record-action"><button className="button secondary" disabled={pending || detailsLoading} onClick={() => openDetails(delivery.deliveryId)} aria-label={`View delivery ${delivery.deliveryId}`}>View details</button></td>
          </tr>)}</tbody></table></div></> : <Empty title={filtered ? 'No deliveries match these filters.' : 'Your delivery workspace starts here.'} text={filtered ? 'Try different filters or clear them to review all saved deliveries.' : 'Choose a recipient and prepare the first delivery. Saved work will appear here for review.'} />}
        {directory.deliveries.length > 0 && <Pagination page={directory.page} total={directory.totalPages} disabled={pending} label="deliveries" change={page => showDirectory(page, filters, uncertain !== null)} />}
      </>}
    </section>
    {detailsLoading && <p role="status">Loading delivery details…</p>}{detailsError && <p className="error-message" role="alert">{detailsError}</p>}
    {details && <section className="recipient-panel delivery-panel" aria-labelledby="delivery-details"><h2 id="delivery-details" ref={detailsHeading} tabIndex={-1}>Delivery #{details.deliveryId}</h2>
      <dl className="delivery-details"><dt>Recipient</dt><dd>{details.clientName} (#{details.recipientId})</dd><dt>Scheduled date</dt><dd>{details.scheduledDate ?? 'Not provided'}</dd><dt>Delivery window</dt><dd>{details.startTimeWindow ?? 'Not provided'} – {details.endTimeWindow ?? 'Not provided'}</dd><dt>Priority</dt><dd>{label(details.priorityLevel)}</dd><dt>Status</dt><dd>{label(details.deliveryStatus)}</dd><dt>Operator</dt><dd>{details.operatorId === null ? 'Unassigned' : `${details.operatorName ?? 'Operator'} (#${details.operatorId})`}</dd><dt>Instructions</dt><dd>{details.specialInstructions || 'Not provided'}</dd></dl>
      <div className="recipient-toolbar"><button className="button secondary" disabled={pending} onClick={() => openDetails(details.deliveryId)}>Refresh delivery details</button><button className="text-button" disabled={pending} onClick={() => { cancel('details'); setDetails(null); directoryHeading.current?.focus(); }}>Close details</button></div>
    </section>}
  </div>;
}
function Empty({ title, text }: { title: string; text: string }) {
  return <div className="delivery-empty"><h3>{title}</h3><p>{text}</p></div>;
}
function Loading({ text }: { text: string }) {
  return <div className="delivery-loading"><p role="status">{text}</p><div aria-hidden="true"><span /><span /></div></div>;
}
function Pagination({ page, total, disabled, label: name, change }: { page: number; total: number; disabled: boolean; label: string; change: (page: number) => void }) {
  return <nav className="delivery-pagination" aria-label={`${name} pagination`}><button className="button secondary" disabled={disabled || page === 0} onClick={() => change(page - 1)}>Previous {name}</button><span>Page {total ? page + 1 : 0} of {total}</span><button className="button secondary" disabled={disabled || page + 1 >= total || page >= 10000} onClick={() => change(page + 1)}>Next {name}</button></nav>;
}
