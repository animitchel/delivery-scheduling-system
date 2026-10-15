import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from './api';
import { validDate } from './deliveries-api';
import { generationApi, generationTimeout } from './route-generation-api';
import type { GenerationClient, GenerationResult } from './route-generation-api';
import type { RoutePage, RouteSummary } from './admin-routes-api';
import type { DriverRoute } from './driver-api';
import './route-generation.css';
function today() { const date = new Date(); return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`; }
function timeout(value?: number): number | null { try { return generationTimeout(value === undefined ? import.meta.env['VITE_ROUTE_GENERATION_TIMEOUT_MS'] as string | undefined : String(value)); } catch { return null; } }
function knownFailure(error: ApiError): string {
  switch (error.routeIssue) {
    case 'NO_WORK': return 'No eligible pending deliveries are available on or before this date. Check your deliveries before generating again.';
    case 'NO_OPERATORS': return 'No available operators are ready for assignment. Check operator availability and active routes.';
    case 'WAREHOUSE_SETUP': return 'An available operator needs warehouse coordinates. Complete operator and warehouse setup before generating.';
    case 'DELIVERY_SETUP': return 'An eligible delivery has incomplete scheduling or location data. Correct it before generating.';
    case 'STALE_INPUTS': return 'Planning inputs changed while routes were being calculated. Reload and review before requesting another plan.';
    case 'COMMIT_CONFLICT': return 'Routes could not be committed. Reload and review existing work before requesting another plan.';
    default: return 'Planning could not proceed. Review current routes and check delivery and operator setup before trying again.';
  }
}
export function RouteGeneration({ client = generationApi, onAuthError, onBusyChange, timeoutMs }: {
  client?: GenerationClient; onAuthError: (error: ApiError) => void; onBusyChange: (busy: boolean) => void; timeoutMs?: number;
}) {
  const deadline = timeout(timeoutMs);
  const [date, setDate] = useState(today);
  const [confirmDate, setConfirmDate] = useState<string | null>(null);
  const [pending, setPending] = useState(false); const [permissionBlocked, setPermissionBlocked] = useState(false);
  const [error, setError] = useState(''); const [message, setMessage] = useState('');
  const [result, setResult] = useState<GenerationResult | null>(null);
  const [recovery, setRecovery] = useState<{ date: string; uncertain: boolean } | null>(null);
  const [reviewReady, setReviewReady] = useState(false);
  const [directory, setDirectory] = useState<RoutePage | null>(null);
  const [directoryDate, setDirectoryDate] = useState(''); const [filterDate, setFilterDate] = useState('');
  const [loading, setLoading] = useState(true); const [readError, setReadError] = useState('');
  const [selected, setSelected] = useState<DriverRoute | null>(null);
  const [detailLoading, setDetailLoading] = useState(false); const [detailError, setDetailError] = useState('');
  const alive = useRef(true); const busy = useRef(false);
  const write = useRef<AbortController | null>(null);
  const lanes = useRef({ directory: { sequence: 0, controller: null as AbortController | null }, details: { sequence: 0, controller: null as AbortController | null } });
  const dialog = useRef<HTMLDialogElement>(null); const submit = useRef<HTMLButtonElement>(null); const keepReviewing = useRef<HTMLButtonElement>(null);
  const focusDirectory = useRef(false);
  const heading = useRef<HTMLHeadingElement>(null); const detailHeading = useRef<HTMLHeadingElement>(null);
  const auth = useCallback((failure: unknown) => { if (failure instanceof ApiError && (failure.status === 401 || failure.passwordChangeRequired)) { onAuthError(failure); return true; } return false; }, [onAuthError]);
  const run = useCallback(async <T,>(lane: 'directory' | 'details', operation: (signal: AbortSignal) => Promise<T>, success: (value: T) => void, failure: () => void) => {
    const current = lanes.current[lane]; const version = ++current.sequence; current.controller?.abort();
    const controller = new AbortController(); current.controller = controller; const timer = window.setTimeout(() => controller.abort(), 30000);
    try { const value = await operation(controller.signal); if (!alive.current || version !== current.sequence) return; if (controller.signal.aborted) throw new ApiError(0); success(value); }
    catch (cause) { if (alive.current && version === current.sequence && !auth(cause)) failure(); }
    finally { window.clearTimeout(timer); }
  }, [auth]);
  const load = useCallback((page: number, value: string, review: boolean) => run('directory', async signal => {
    let response = await client.directory(page, value, signal);
    if (page > 0 && response.routes.length === 0 && !signal.aborted) response = await client.directory(0, value, signal);
    return response;
  }, response => { setDirectory(response); setLoading(false); if (review) setReviewReady(true); }, () => { setLoading(false); setReviewReady(false); setReadError('Routes could not be loaded. Please reload the directory.'); }), [client, run]);
  useEffect(() => { alive.current = true; const reads = lanes.current; void Promise.resolve().then(() => { if (alive.current) return load(0, '', false); }); return () => { alive.current = false; reads.directory.controller?.abort(); reads.details.controller?.abort(); write.current?.abort(); }; }, [load]);
  useEffect(() => { if (confirmDate) { dialog.current?.showModal(); keepReviewing.current?.focus(); } else dialog.current?.close(); }, [confirmDate]);
  useEffect(() => { if (selected) detailHeading.current?.focus(); }, [selected]);
  useEffect(() => { if (directory && focusDirectory.current) { focusDirectory.current = false; heading.current?.focus(); heading.current?.scrollIntoView?.({ block: 'start' }); } }, [directory]);
  function closeConfirmation() { if (busy.current) return; dialog.current?.close(); setConfirmDate(null); submit.current?.focus(); }
  function prepare() {
    if (busy.current || recovery || permissionBlocked || deadline === null) return;
    if (!validDate(date)) { setError('Enter a valid planning cutoff date between years 1000 and 9999.'); return; }
    setError(''); setConfirmDate(date);
  }
  function clearDetails() { ++lanes.current.details.sequence; lanes.current.details.controller?.abort(); setSelected(null); setDetailLoading(false); setDetailError(''); }
  function reload(page = 0, value = directoryDate, review = recovery !== null) {
    if (busy.current) return;
    if (recovery) { value = recovery.date; review = true; setDirectoryDate(value); setFilterDate(value); }
    setLoading(true); setDirectory(null); setReadError(''); setReviewReady(false); clearDetails(); void load(page, value, review);
  }
  function changePage(page: number) { if (busy.current) return; focusDirectory.current = true; reload(page); }
  function review() {
    if (!recovery || busy.current) return;
    setDirectoryDate(recovery.date); setFilterDate(recovery.date); reload(0, recovery.date, true); heading.current?.focus();
  }
  function inspect(id: number) {
    if (busy.current) return;
    clearDetails(); setDetailLoading(true);
    void run('details', signal => client.route(id, signal), route => { setSelected(route); setDetailLoading(false); }, () => { setDetailLoading(false); setDetailError('Route details could not be loaded. Reload routes and try again.'); });
  }
  function recover(value: string, uncertain: boolean) {
    ++lanes.current.directory.sequence; lanes.current.directory.controller?.abort();
    setRecovery({ date: value, uncertain }); setDirectoryDate(value); setFilterDate(value);
    setDirectory(null); setLoading(false); setReviewReady(false); setReadError('');
  }
  async function generate(value: string) {
    if (busy.current || recovery || permissionBlocked || deadline === null) return;
    busy.current = true; setPending(true); onBusyChange(true); dialog.current?.close(); setConfirmDate(null);
    setError(''); setMessage(''); setResult(null); setReviewReady(false); clearDetails();
    const controller = new AbortController(); write.current = controller; const timer = window.setTimeout(() => controller.abort(), deadline);
    try {
      const response = await client.generate(value, controller.signal);
      if (!alive.current) return; if (controller.signal.aborted) throw new ApiError(0);
      setResult(response); setMessage(`${response.routes.length} ${response.routes.length === 1 ? 'route' : 'routes'} generated and assigned for ${value}.`);
      setDirectoryDate(value); setFilterDate(value); setDirectory(null); setLoading(true); setReadError(''); void load(0, value, false);
    } catch (failure) {
      if (!alive.current || auth(failure)) return;
      const status = failure instanceof ApiError ? failure.status : 0;
      if (status === 400) setError('Check the planning date before trying again.');
      else if (status === 403) { setPermissionBlocked(true); setError('Administrator access is required. Refresh your session before trying again.'); }
      else if (status === 413) setError('Too many eligible deliveries for one planning operation. Check the pending workload and configured planning limit before trying again.');
      else if (status === 422) setError('The provider-call budget was exceeded. Check the workload and planning settings before trying again.');
      else if (status === 429) setError('Planning is busy. Wait and review current routes before requesting another plan.');
      else if (status === 409) {
        setError(knownFailure(failure as ApiError));
        if (!(failure instanceof ApiError && ['NO_WORK', 'NO_OPERATORS', 'WAREHOUSE_SETUP', 'DELIVERY_SETUP'].includes(failure.routeIssue ?? ''))) { recover(value, false); }
      } else { recover(value, true); setError('The generation outcome could not be confirmed. Review existing routes before deciding on another request.'); }
    } finally { window.clearTimeout(timer); busy.current = false; onBusyChange(false); if (alive.current) setPending(false); }
  }
  const routeLink = (route: RouteSummary, generated: boolean) => <Link to={`/admin/routes?routeId=${route.routeId}`} className="button secondary generation-route-link" aria-disabled={pending} onClick={event => { if (busy.current) event.preventDefault(); }}>{generated ? 'Open generated route' : 'Open route'} #{route.routeId}</Link>;
  const frozen = pending || recovery !== null || permissionBlocked;
  return <div className="route-generation">
    <section className="page-intro"><p className="eyebrow">Route planning</p><h1>Generate and assign routes.</h1><p className="intro-text">Turn eligible pending deliveries into active routes for available operators.</p></section>
    {deadline === null && <p className="error-message" role="alert">Generation is unavailable because its timeout configuration is invalid. Contact the administrator.</p>}
    {error && <p className="error-message" role="alert">{error}</p>}{message && <p role="status">{message}</p>}
    <section className="recipient-panel generation-planning" aria-labelledby="planning-date"><h2 id="planning-date">Choose the planning cutoff</h2>
      <p>Include eligible, unrouted pending deliveries scheduled on or before this date, including overdue work. Operators are selected automatically.</p>
      <form onSubmit={event => { event.preventDefault(); prepare(); }}><label htmlFor="generation-date">Planning cutoff date<input id="generation-date" type="date" required min="1000-01-01" max="9999-12-31" disabled={frozen} value={date} onChange={event => setDate(event.target.value)} /></label>
        <p>Generation immediately assigns operators and starts routes and deliveries. Travel estimates do not guarantee delivery-window feasibility.</p>
        <button className="button" ref={submit} disabled={frozen || deadline === null}>Review assignment</button>
      </form>
      {pending && <><p role="status">Generating routes… This can take time while travel information is calculated.</p><p>Stopping the wait does not undo assignments. The server may still save routes.</p><button className="button secondary" onClick={() => write.current?.abort()}>Stop waiting and review</button></>}
    </section>
    <dialog className="generation-dialog" ref={dialog} aria-labelledby="generation-confirm-title" onCancel={() => { setConfirmDate(null); submit.current?.focus(); }}>
      <h2 id="generation-confirm-title">Generate and assign routes?</h2><p>Use planning cutoff <strong>{confirmDate}</strong>, including eligible overdue deliveries.</p><p>This immediately assigns available operators, starts the generated routes and deliveries, and marks operators on delivery.</p>
      <p>This is an assignment action. Route estimates cannot guarantee that every delivery window is achievable.</p>
      <div className="recipient-toolbar"><button className="button" disabled={pending} onClick={() => { if (confirmDate) void generate(confirmDate); }}>Confirm generation and assignment</button><button className="button secondary" ref={keepReviewing} disabled={pending} onClick={closeConfirmation}>Keep reviewing</button></div>
    </dialog>
    {recovery && <section className="recipient-panel generation-recovery" aria-labelledby="generation-recovery"><h2 id="generation-recovery">Review before generating again</h2>
      <p>{recovery.uncertain ? 'Routes may already have been saved.' : 'Planning inputs may have changed or another plan may have committed.'} Review routes for {recovery.date} across all statuses, including completed work.</p>
      <p>An empty list or matching routes cannot prove which request saved work. Inspect route members and additional pages before deciding whether another generation is needed.</p>
      <button className="button" disabled={pending} onClick={review}>Review existing routes for this date</button>
      <button className="button secondary" disabled={!reviewReady || loading || pending} onClick={() => { setRecovery(null); setReviewReady(false); setError(''); setMessage('Review completed. Another generation can assign newly pending work.'); }}>I reviewed results; allow another request</button>
    </section>}
    {result && <section className="recipient-panel" aria-labelledby="generated-routes"><h2 id="generated-routes">Generated routes</h2><p>Assignment was saved for {result.scheduledDate}. These are acknowledgement summaries; open a route for its current progress.</p>
      <ul className="generation-results">{result.routes.map(route => <li key={route.routeId}><strong>Route #{route.routeId} · {route.operatorName}</strong><p>{route.stopCount} stops assigned</p>{routeLink(route, true)}</li>)}</ul>
    </section>}
    <section className="recipient-panel" aria-labelledby="generation-directory"><h2 id="generation-directory" ref={heading} tabIndex={-1} aria-describedby={directory ? 'generation-directory-count' : undefined}>Review existing routes</h2>
      <form className="recipient-toolbar generation-directory-toolbar" onSubmit={event => { event.preventDefault(); if (pending || recovery) return; if (filterDate && !validDate(filterDate)) { setReadError('Enter a valid route filter date.'); return; } setDirectoryDate(filterDate); reload(0, filterDate, false); }}>
        <label htmlFor="generation-filter-date">Routes generated for date<input id="generation-filter-date" type="date" min="1000-01-01" max="9999-12-31" disabled={pending || recovery !== null} value={filterDate} onChange={event => setFilterDate(event.target.value)} /></label>
        <button className="button secondary" disabled={pending || recovery !== null}>Apply route date</button><button type="button" className="text-button" disabled={pending || recovery !== null} onClick={() => { setFilterDate(''); setDirectoryDate(''); reload(0, '', false); }}>All route dates</button>
        <button type="button" className="text-button" disabled={pending} onClick={() => { if (recovery) review(); else reload(); }}>Reload route directory</button>
      </form><p>Includes planned, in-progress and completed routes.</p>
      {readError && <p className="error-message" role="alert">{readError}</p>}
      {loading ? <p role="status">Loading route summaries…</p> : directory && <>
        <p id="generation-directory-count">{directory.totalElements} {directory.totalElements === 1 ? 'route' : 'routes'} found. Page {directory.totalPages ? directory.page + 1 : 0} of {directory.totalPages}.</p>
        {directory.routes.length ? <><p id="generation-directory-help">Inspect stops here, or open a route in the administrator workspace for its current progress.</p><div className="recipient-table generation-directory-results" role="region" aria-label="Generation route directory" aria-describedby="generation-directory-help"><table role="table" aria-label="Routes for generation review"><thead role="rowgroup"><tr role="row">
          <th scope="col" id="generation-route-id-column">Route</th><th scope="col" id="generation-route-operator-column">Operator</th><th scope="col" id="generation-route-date-column">Date</th><th scope="col" id="generation-route-status-column">Status</th><th scope="col" id="generation-route-progress-column">Progress</th><th scope="col" id="generation-route-review-column">Review</th>
        </tr></thead><tbody role="rowgroup">{directory.routes.map(route => <tr key={route.routeId} role="row">
          <th role="rowheader" scope="row" id={`generation-route-row-${route.routeId}`}><strong>Route #{route.routeId}</strong></th>
          <td role="cell" headers={`generation-route-row-${route.routeId} generation-route-operator-column`}><span className="generation-mobile-label" aria-hidden="true">Operator</span><span>{route.operatorName}</span></td>
          <td role="cell" headers={`generation-route-row-${route.routeId} generation-route-date-column`}><span className="generation-mobile-label" aria-hidden="true">Scheduled date</span><span>{route.scheduledDate}</span></td>
          <td role="cell" headers={`generation-route-row-${route.routeId} generation-route-status-column`}><span className="generation-mobile-label" aria-hidden="true">Status</span><span className={`generation-route-status generation-route-status-${route.routeStatus.toLowerCase()}`}>{({ PLANNED: 'Planned', IN_PROGRESS: 'In progress', COMPLETED: 'Completed' })[route.routeStatus]}</span></td>
          <td role="cell" headers={`generation-route-row-${route.routeId} generation-route-progress-column`}><span className="generation-mobile-label" aria-hidden="true">Progress</span><span>{route.finishedCount} of {route.stopCount} stops</span></td>
          <td role="cell" className="generation-review-actions" headers={`generation-route-row-${route.routeId} generation-route-review-column`}><button className="button secondary" disabled={pending || detailLoading} onClick={() => inspect(route.routeId)} aria-label={`Inspect route ${route.routeId}`}>Inspect stops</button>{routeLink(route, false)}</td>
        </tr>)}</tbody></table></div></> : <p>No routes match this date filter.</p>}
        <nav className="recipient-toolbar generation-pagination" aria-label="generation routes pagination"><button className="button secondary" disabled={pending || directory.page === 0} onClick={() => changePage(directory.page - 1)}>Previous generation routes</button><span>Page {directory.totalPages ? directory.page + 1 : 0} of {directory.totalPages}</span><button className="button secondary" disabled={pending || directory.page + 1 >= directory.totalPages || directory.page >= 10000} onClick={() => changePage(directory.page + 1)}>Next generation routes</button></nav>
      </>}
    </section>
    {detailLoading && <p role="status">Loading current route stops…</p>}{detailError && <p role="alert" className="error-message">{detailError}</p>}
    {selected && <section className="recipient-panel" aria-labelledby="generation-stops"><h2 id="generation-stops" ref={detailHeading} tabIndex={-1}>Inspect route #{selected.routeId}</h2><p>{selected.scheduledDate} · {selected.operatorName} · {selected.routeStatus.replaceAll('_', ' ')}</p>
      <p>Travel figures are estimates. Missing travel times remain unavailable; delivery-window feasibility is not guaranteed.</p>
      <ol className="generation-stops">{selected.deliveries.map(stop => <li key={stop.deliveryId}><strong>{stop.clientName}</strong><p>Delivery #{stop.deliveryId} · Recipient #{stop.recipientId} · {stop.status.replaceAll('_', ' ')}</p><p>{stop.fromPrevDurationText ?? 'Travel time unavailable'}{stop.fromPrevDistanceKm === null ? '' : ` · ${stop.fromPrevDistanceKm.toFixed(1)} km`}</p></li>)}</ol>
      {!selected.deliveries.length && <p>No stops on this route.</p>}
      <button className="button secondary" disabled={pending} onClick={() => { clearDetails(); heading.current?.focus(); }}>Close route inspection</button>
    </section>}
  </div>;
}
