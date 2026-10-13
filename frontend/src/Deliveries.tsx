import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from './api';
import { deliveriesApi, deliveryStatuses, inputError, validDate } from './deliveries-api';
import type { DeliveriesClient, DeliveryDetails, DeliveryFilters, DeliveryInput, DeliveryPage } from './deliveries-api';
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
  function recipientSearch(page = 0, term = query) {
    if (busy.current || uncertain) return;
    cancel('selection'); setSelecting(false); setRecipientLoading(true); setRecipients(null); setRecipientError('');
    void loadRecipients(page, term);
  }
  function choose(id: number) {
    if (busy.current || uncertain) return;
    setSelecting(true); setSelected(null); setRecipientError(''); setSaveError(''); setMessage('');
    void run('selection', signal => recipientClient.read(id, signal), result => {
      setSelected(result.recipient); setSelecting(false); createHeading.current?.focus();
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
    const invalid = inputError(input); if (invalid) { setSaveError(invalid); return; }
    const context = { recipientId: input.recipientId, scheduledDate: input.scheduledDate, name: `${selected!.firstName} ${selected!.lastName}` };
    busy.current = true; setPending(true); onBusyChange(true); setSaveError(''); setMessage('');
    const controller = new AbortController(); write.current = controller;
    const timer = window.setTimeout(() => controller.abort(), 15000);
    try {
      const result = await client.create(input, controller.signal);
      if (!alive.current) return; if (controller.signal.aborted) throw new ApiError(0);
      setMessage(`Delivery #${result.deliveryId} created.`); setSelected(null); setDraft({ ...blank });
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
  return <>
    <section className="page-intro"><p className="eyebrow">Delivery preparation</p><h1>Prepare the next delivery.</h1><p className="intro-text">Choose a recipient, set the delivery window and review saved work.</p></section>
    {message && <p role="status">{message}</p>}{saveError && <p className="error-message" role="alert">{saveError}</p>}
    {uncertain && <section className="recipient-panel delivery-panel delivery-recovery" aria-labelledby="save-review"><h2 id="save-review">Check before submitting again</h2>
      <p>Review deliveries for {uncertain.name} on {uncertain.scheduledDate}, across all statuses. Work may already have been assigned.</p>
      <p>An empty list or a matching delivery cannot prove which request saved it. Inspect details and additional pages before deciding whether another creation is needed.</p>
      <button className="button" disabled={pending} onClick={reviewUncertain}>Review possible saved deliveries</button>
      <button className="button secondary" disabled={!reviewReady || directoryLoading} onClick={() => { setUncertain(null); setReviewReady(false); setSaveError('Review completed. Another submission will create a new delivery.'); }}>I reviewed results; allow another submission</button>
    </section>}
    <section className="recipient-panel delivery-panel" aria-labelledby="choose-recipient"><h2 id="choose-recipient">Choose a recipient</h2>
      <form className="recipient-toolbar" onSubmit={event => { event.preventDefault(); if (frozen) return; setQuery(search.trim()); recipientSearch(0, search.trim()); }}>
        <label htmlFor="delivery-recipient-search">Search recipient name or address<input id="delivery-recipient-search" type="search" maxLength={100} value={search} disabled={frozen} onChange={event => setSearch(event.target.value)} /></label>
        <button className="button secondary" disabled={frozen}>Search recipients</button><button type="button" className="text-button" disabled={frozen} onClick={() => recipientSearch()}>Reload recipients</button>
      </form>
      {recipientError && <p role="alert" className="error-message">{recipientError}</p>}
      {recipientLoading ? <p role="status">Loading recipients…</p> : recipients && <>
        {recipients.recipients.length ? <ul className="delivery-recipient-list">{recipients.recipients.map(recipient => <li key={recipient.recipientId}>
          <div><strong>{recipient.firstName} {recipient.lastName}</strong><p>{recipient.address}</p><small>Recipient #{recipient.recipientId}</small></div>
          <button className="button secondary" disabled={frozen || selecting} onClick={() => choose(recipient.recipientId)} aria-label={`Choose ${recipient.firstName} ${recipient.lastName}, recipient ${recipient.recipientId}`}>Choose recipient</button>
        </li>)}</ul> : <p>No recipients match your search.</p>}
        <Pagination page={recipients.page} total={recipients.totalPages} disabled={frozen || selecting} label="recipients" change={recipientSearch} />
      </>}{selecting && <p role="status">Loading current recipient details…</p>}
    </section>
    <section className="recipient-panel delivery-panel" aria-labelledby="delivery-create"><h2 id="delivery-create" tabIndex={-1} ref={createHeading}>Create delivery</h2>
      {selected ? <div className="recipient-notice"><strong>Selected recipient: {selected.firstName} {selected.lastName}</strong><p>{selected.address}</p><span>Recipient #{selected.recipientId}</span></div> : <p>Choose a recipient from the search results above.</p>}
      <p>New deliveries are pending and unassigned. Route generation assigns operators. Past dates remain available for overdue work.</p>
      <form onSubmit={event => { event.preventDefault(); void save(); }}>
        <fieldset disabled={frozen || permissionBlocked}><legend>Delivery details</legend><div className="recipient-fields">
          <div><label htmlFor="delivery-date">Scheduled date</label><input id="delivery-date" type="date" required min="1000-01-01" max="9999-12-31" value={draft.scheduledDate} onChange={event => setDraft({ ...draft, scheduledDate: event.target.value })} /></div>
          <div><label htmlFor="delivery-priority">Priority</label><select id="delivery-priority" value={draft.priorityLevel} onChange={event => setDraft({ ...draft, priorityLevel: event.target.value as Draft['priorityLevel'] })}><option value="HIGH">High</option><option value="MEDIUM">Medium</option><option value="LOW">Low</option></select></div>
          <div><label htmlFor="delivery-start">Start time</label><input id="delivery-start" type="time" step="1" required value={draft.startTimeWindow} onChange={event => setDraft({ ...draft, startTimeWindow: event.target.value })} /></div>
          <div><label htmlFor="delivery-end">End time</label><input id="delivery-end" type="time" step="1" required value={draft.endTimeWindow} onChange={event => setDraft({ ...draft, endTimeWindow: event.target.value })} /></div>
          <div className="delivery-instructions"><label htmlFor="delivery-instructions">Special instructions (optional)</label><textarea id="delivery-instructions" maxLength={255} value={draft.specialInstructions ?? ''} onChange={event => setDraft({ ...draft, specialInstructions: event.target.value })} /><small>{draft.specialInstructions?.length ?? 0}/255 characters</small></div>
        </div></fieldset>
        <p>Use a window that ends after it starts on the same day.</p><button className="button" disabled={frozen || permissionBlocked || selecting || !selected}>Create delivery</button>
        {pending && <p role="status">Saving delivery…</p>}
      </form>
    </section>
    <section className="recipient-panel delivery-panel" aria-labelledby="delivery-directory"><h2 id="delivery-directory" tabIndex={-1} ref={directoryHeading}>Delivery directory</h2>
      <form className="recipient-toolbar" onSubmit={event => { event.preventDefault(); if (frozen) return; if (filterDate && !validDate(filterDate)) { setDirectoryError('Enter a valid filter date.'); return; } applyFilters({ ...filters, scheduledDate: filterDate, status: filterStatus }, filterName); }}>
        <label htmlFor="delivery-filter-date">Filter scheduled date<input id="delivery-filter-date" type="date" min="1000-01-01" max="9999-12-31" value={filterDate} disabled={frozen} onChange={event => setFilterDate(event.target.value)} /></label>
        <label htmlFor="delivery-filter-status">Filter status<select id="delivery-filter-status" value={filterStatus} disabled={frozen} onChange={event => setFilterStatus(event.target.value as DeliveryFilters['status'])}><option value="">All statuses</option>{deliveryStatuses.map(status => <option value={status} key={status}>{label(status)}</option>)}</select></label>
        <button className="button secondary" disabled={frozen}>Apply filters</button><button type="button" className="text-button" disabled={frozen} onClick={() => applyFilters({ ...all }, '')}>Clear filters</button>
      </form>
      <div className="recipient-toolbar"><button className="button secondary" disabled={frozen || !selected} onClick={() => { if (selected) applyFilters({ ...filters, recipientId: selected.recipientId }, `${selected.firstName} ${selected.lastName}`); }}>Filter by selected recipient</button>
        <button className="text-button" disabled={pending} onClick={() => { if (uncertain) reviewUncertain(); else showDirectory(); }}>Reload deliveries</button></div>
      {filters.recipientId !== null && <p>Recipient filter: {filterName} (#{filters.recipientId})</p>}
      {directoryError && <p role="alert" className="error-message">{directoryError}</p>}
      {directoryLoading ? <p role="status">Loading deliveries…</p> : directory && <>
        <p>{directory.totalElements} {directory.totalElements === 1 ? 'delivery' : 'deliveries'} found.</p>
        {directory.deliveries.length ? <><p id="delivery-scroll-hint">On smaller screens, scroll the table to see all details.</p><div className="recipient-table" role="region" aria-label="Deliveries" aria-describedby="delivery-scroll-hint" tabIndex={0}>
          <table><thead><tr><th scope="col">Delivery</th><th scope="col">Recipient</th><th scope="col">Date and window</th><th scope="col">Priority</th><th scope="col">Status</th><th scope="col">Action</th></tr></thead><tbody>{directory.deliveries.map(delivery => <tr key={delivery.deliveryId}>
            <td>#{delivery.deliveryId}</td><td>{delivery.firstName} {delivery.lastName}</td><td>{delivery.scheduledDate ?? 'Date not provided'}<br />{delivery.startTimeWindow ?? 'Start not provided'} – {delivery.endTimeWindow ?? 'End not provided'}</td><td>{label(delivery.priorityLevel)}</td><td>{label(delivery.deliveryStatus)}</td>
            <td><button className="button secondary" disabled={pending || detailsLoading} onClick={() => openDetails(delivery.deliveryId)} aria-label={`View delivery ${delivery.deliveryId}`}>View details</button></td>
          </tr>)}</tbody></table></div></> : <p>No deliveries match these filters.</p>}
        <Pagination page={directory.page} total={directory.totalPages} disabled={pending} label="deliveries" change={page => showDirectory(page, filters, uncertain !== null)} />
      </>}
    </section>
    {detailsLoading && <p role="status">Loading delivery details…</p>}{detailsError && <p className="error-message" role="alert">{detailsError}</p>}
    {details && <section className="recipient-panel delivery-panel" aria-labelledby="delivery-details"><h2 id="delivery-details" ref={detailsHeading} tabIndex={-1}>Delivery #{details.deliveryId}</h2>
      <dl className="delivery-details"><dt>Recipient</dt><dd>{details.clientName} (#{details.recipientId})</dd><dt>Scheduled date</dt><dd>{details.scheduledDate ?? 'Not provided'}</dd><dt>Delivery window</dt><dd>{details.startTimeWindow ?? 'Not provided'} – {details.endTimeWindow ?? 'Not provided'}</dd><dt>Priority</dt><dd>{label(details.priorityLevel)}</dd><dt>Status</dt><dd>{label(details.deliveryStatus)}</dd><dt>Operator</dt><dd>{details.operatorId === null ? 'Unassigned' : `${details.operatorName ?? 'Operator'} (#${details.operatorId})`}</dd><dt>Instructions</dt><dd>{details.specialInstructions || 'Not provided'}</dd></dl>
      <div className="recipient-toolbar"><button className="button secondary" disabled={pending} onClick={() => openDetails(details.deliveryId)}>Refresh delivery details</button><button className="text-button" disabled={pending} onClick={() => { cancel('details'); setDetails(null); directoryHeading.current?.focus(); }}>Close details</button></div>
    </section>}
  </>;
}
function Pagination({ page, total, disabled, label: name, change }: { page: number; total: number; disabled: boolean; label: string; change: (page: number) => void }) {
  return <nav className="recipient-toolbar" aria-label={`${name} pagination`}><button className="button secondary" disabled={disabled || page === 0} onClick={() => change(page - 1)}>Previous {name}</button><span>Page {total ? page + 1 : 0} of {total}</span><button className="button secondary" disabled={disabled || page + 1 >= total || page >= 10000} onClick={() => change(page + 1)}>Next {name}</button></nav>;
}
