import { useCallback, useEffect, useRef, useState } from 'react';
import { RouteStops, StopDetailsDialog } from './RouteView';
import { ApiError } from './api';
import { adminRoutesApi } from './admin-routes-api';
import type { AdminRoutesClient, RoutePage } from './admin-routes-api';
import type { DriverRoute, Details, Recipient, Stop } from './driver-api';
import './driver.css';
import './accounts.css';
import './admin-routes.css';
type Info = { stop: Stop; delivery: Details; recipient: Recipient };
export function AdminRoutes({ client = adminRoutesApi, onAuthError, onBusyChange, initialRouteId }: { client?: AdminRoutesClient; onAuthError: (error: ApiError) => void; onBusyChange?: (busy: boolean) => void; initialRouteId?: number | undefined }) {
  const [directory, setDirectory] = useState<RoutePage | null>(null);
  const [route, setRoute] = useState<DriverRoute | null>(null);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const [info, setInfo] = useState<Info | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [cancelStop, setCancelStop] = useState<Stop | null>(null);
  const reads = useRef<AbortController | null>(null);
  const details = useRef<AbortController | null>(null);
  const sequence = useRef(0);
  const write = useRef(false);
  const active = useRef(true);
  const dialog = useRef<HTMLDialogElement>(null);
  const returnFocus = useRef<HTMLButtonElement | null>(null);
  const auth = useCallback((error: unknown) => {
    if (error instanceof ApiError && (error.status === 401 || error.passwordChangeRequired)) { onAuthError(error); return true; }
    return false;
  }, [onAuthError]);
  const clearDetails = useCallback(() => { details.current?.abort(); setInfo(null); setDetailLoading(false); setDetailError(''); }, []);
  const mutation = useRef<AbortController | null>(null);
  const summary = useRef<HTMLDivElement | null>(null);
  const focusSelection = useRef(false);
  const directoryHeading = useRef<HTMLHeadingElement>(null);
  const focusDirectory = useRef(false);
  useEffect(() => {
    if (directory && focusDirectory.current) { focusDirectory.current = false; directoryHeading.current?.focus(); directoryHeading.current?.scrollIntoView?.({ block: 'start' }); }
  }, [directory]);
  useEffect(() => {
    if (route && focusSelection.current) { focusSelection.current = false; summary.current?.focus(); summary.current?.scrollIntoView?.({ block: 'start' }); }
  }, [route]);
  const loadRoutes = useCallback((selected?: number, page = 0) => {
    reads.current?.abort(); const controller = new AbortController(); reads.current = controller;
    const version = ++sequence.current;
    let timedOut = false; const timer = window.setTimeout(() => { timedOut = true; controller.abort(); }, 30000);
    void Promise.resolve().then(async () => {
      let list = await client.directory(page, controller.signal);
      if (controller.signal.aborted || !active.current || version !== sequence.current) return;
      if (page > 0 && page >= list.totalPages) list = await client.directory(Math.max(0,list.totalPages-1), controller.signal);
      const chosen = selected ?? list.routes.find(r => r.routeStatus === 'IN_PROGRESS')?.routeId ?? list.routes[0]?.routeId;
      const current = chosen ? await client.route(chosen, controller.signal) : null;
      if (!active.current || controller.signal.aborted || version !== sequence.current) return;
      setDirectory(list); setRoute(current); setBlocked(false); setLoading(false);
    }).catch((error: unknown) => {
      if (!active.current || (controller.signal.aborted && !timedOut) || version !== sequence.current) return;
      setDirectory(null); setRoute(null); setBlocked(true); setLoading(false);
      if (!auth(error)) setMessage(error instanceof ApiError && error.status === 403 ? 'Administrator access is required.' : error instanceof ApiError && error.status === 404 ? 'This route is no longer available. Reload routes.' : 'Routes could not be loaded. Please try again.');
    }).finally(() => window.clearTimeout(timer));
  }, [client, auth]);
  function refresh(selected?: number, page = directory?.page ?? 0) {
    clearDetails(); dialog.current?.close(); setCancelStop(null); setLoading(true); setMessage(''); setRoute(null); setDirectory(null);
    loadRoutes(selected, page);
  }
  useEffect(() => {
    active.current = true; if (initialRouteId) focusSelection.current = true; loadRoutes(initialRouteId);
    return () => { active.current = false; reads.current?.abort(); details.current?.abort(); mutation.current?.abort(); };
  }, [loadRoutes, initialRouteId]);
  function openDetails(stop: Stop, button: HTMLButtonElement) {
    clearDetails(); const controller = new AbortController(); details.current = controller;
    returnFocus.current = button; setDetailLoading(true);
    let timedOut = false; const timer = window.setTimeout(() => { timedOut = true; controller.abort(); }, 30000);
    void client.details(stop, route!.operatorId, controller.signal).then(result => {
      if (!active.current || controller.signal.aborted) return;
      setInfo({ stop, ...result }); setDetailLoading(false);
    }).catch((error: unknown) => {
      if (!active.current || (controller.signal.aborted && !timedOut)) return;
      setDetailLoading(false);
      if (error instanceof ApiError && (error.status === 404 || error.status === 409)) setBlocked(true);
      if (!auth(error)) setDetailError(error instanceof ApiError && error.status === 404 ? 'This stop is no longer available. Reload the route.' : error instanceof ApiError && error.status === 409 ? 'This stop has changed. Reload the route.' : 'Stop details could not be loaded. Close and try again.');
    }).finally(() => window.clearTimeout(timer));
    dialog.current?.showModal();
  }
  function closeDetails() { clearDetails(); dialog.current?.close(); returnFocus.current?.focus(); }
  async function update(stop: Stop, status: 'COMPLETED' | 'CANCELLED') {
    if (write.current || blocked || !route || route.routeStatus !== 'IN_PROGRESS' || stop.status !== 'IN_PROGRESS') return;
    write.current = true; onBusyChange?.(true); setBusy(true); setCancelStop(null); clearDetails(); dialog.current?.close();
    const controller = new AbortController(); mutation.current = controller; const timeout = window.setTimeout(() => controller.abort(), 15000);
    const selected = route.routeId;
    try {
      await client.update(stop.deliveryId, status, controller.signal);
      if (active.current) { setBlocked(true); refresh(selected); }
    } catch (error) {
      if (!active.current) return;
      setBlocked(true); setRoute(null); setDirectory(null);
      if (!auth(error)) setMessage(error instanceof ApiError && error.status === 404 ? 'This route or delivery is no longer available. Reload routes.' : error instanceof ApiError && error.status === 409 ? 'This delivery has changed. Reload routes before another action.' : 'The update outcome is uncertain. Reload routes before another action.');
    } finally { window.clearTimeout(timeout); write.current = false; onBusyChange?.(false); if (active.current) setBusy(false); }
  }
  return <section className="driver-routes admin-routes"><div className="driver-heading"><div><p className="eyebrow">Administrator workspace</p><h1>Routes</h1></div><button className="button secondary" disabled={busy || loading} onClick={() => refresh(route?.routeId ?? initialRouteId)}>Reload routes</button></div>
    {message && <p role="alert" className="error-message">{message}</p>}
    {loading ? <p role="status">Loading routes…</p> : directory?.routes.length === 0 && !route && !message ? <div className="route-empty"><h2>No routes found</h2><p>Generated routes will appear here.</p></div> : <>
      {directory && <section className="route-directory" aria-labelledby="admin-directory-title"><h2 id="admin-directory-title" ref={directoryHeading} tabIndex={-1} aria-describedby="admin-route-page-info">Route directory</h2><p className="admin-route-directory-help">Review assigned work and open a route to inspect its stops.</p>
        <div className="accounts-table admin-route-directory-results"><table role="table" aria-label="Routes and assignment progress"><thead role="rowgroup"><tr role="row">
          <th scope="col" id="admin-route-id-column">Route</th><th scope="col" id="admin-route-operator-column">Operator</th><th scope="col" id="admin-route-date-column">Date</th><th scope="col" id="admin-route-status-column">Status</th><th scope="col" id="admin-route-progress-column">Progress</th><th scope="col" id="admin-route-action-column">Action</th>
        </tr></thead><tbody role="rowgroup">{directory.routes.map(item => <tr key={item.routeId} role="row" data-selected={item.routeId === route?.routeId}>
          <th scope="row" role="rowheader" id={`admin-route-row-${item.routeId}`}><span className="admin-route-id">Route #{item.routeId}</span>{item.routeId === route?.routeId && <span className="admin-route-current" aria-hidden="true">Viewing</span>}</th>
          <td role="cell" headers={`admin-route-row-${item.routeId} admin-route-operator-column`}><span className="admin-route-mobile-label" aria-hidden="true">Operator</span><span>{item.operatorName}</span></td>
          <td role="cell" headers={`admin-route-row-${item.routeId} admin-route-date-column`}><span className="admin-route-mobile-label" aria-hidden="true">Scheduled date</span><span>{item.scheduledDate}</span></td>
          <td role="cell" headers={`admin-route-row-${item.routeId} admin-route-status-column`}><span className="admin-route-mobile-label" aria-hidden="true">Status</span><span className={`admin-route-status admin-route-status-${item.routeStatus.toLowerCase()}`}>{({ PLANNED: 'Planned', IN_PROGRESS: 'In progress', COMPLETED: 'Completed' })[item.routeStatus]}</span></td>
          <td role="cell" headers={`admin-route-row-${item.routeId} admin-route-progress-column`}><span className="admin-route-mobile-label" aria-hidden="true">Progress</span><span>{item.finishedCount} of {item.stopCount} stops</span></td>
          <td role="cell" className="admin-route-action" headers={`admin-route-row-${item.routeId} admin-route-action-column`}><button className="button secondary" disabled={busy} aria-current={item.routeId === route?.routeId ? 'true' : undefined} onClick={() => { focusSelection.current = true; refresh(item.routeId); }}>View route #{item.routeId}</button></td>
        </tr>)}</tbody></table></div>
        <div className="account-pagination admin-route-pagination"><button className="button secondary" disabled={busy || directory.page === 0} onClick={() => { focusDirectory.current = true; refresh(undefined,directory.page-1); }}>Previous routes</button><span id="admin-route-page-info">Page {directory.page+1} of {directory.totalPages} · {directory.totalElements} routes</span><button className="button secondary" disabled={busy || directory.page+1 >= directory.totalPages} onClick={() => { focusDirectory.current = true; refresh(undefined,directory.page+1); }}>Next routes</button></div></section>}

      {route && directory && !directory.routes.some(item => item.routeId === route.routeId) && <p>Viewing route #{route.routeId} from a direct link; it is outside this directory page.</p>}
      {route && <RouteStops route={route} summaryRef={summary} busy={busy} blocked={blocked} cancelStop={cancelStop} onDetails={openDetails} onComplete={stop => { void update(stop, 'COMPLETED'); }} onCancel={setCancelStop} onConfirm={stop => { void update(stop, 'CANCELLED'); }} onKeep={() => setCancelStop(null)} />}
    </>}
    <StopDetailsDialog dialog={dialog} loading={detailLoading} error={detailError} info={info} onClose={closeDetails} onDismiss={clearDetails} />
  </section>;
}
