import { useCallback, useEffect, useRef, useState } from 'react';
import { RouteStops, StopDetailsDialog } from './RouteView';
import { ApiError } from './api';
import { adminRoutesApi } from './admin-routes-api';
import type { AdminRoutesClient, RoutePage } from './admin-routes-api';
import type { DriverRoute, Details, Recipient, Stop } from './driver-api';
import './driver.css';
import './accounts.css';
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
      {directory && <div className="route-directory"><h2>Route directory</h2><p className="table-scroll-hint">Scroll across the table to see all route details and actions.</p><div className="accounts-table" role="region" aria-label="Route directory table" tabIndex={0}><table><thead><tr><th>Route</th><th>Operator</th><th>Date</th><th>Status</th><th>Progress</th><th>Action</th></tr></thead><tbody>{directory.routes.map(item => <tr key={item.routeId}><th scope="row">#{item.routeId}</th><td>{item.operatorName}</td><td>{item.scheduledDate}</td><td>{item.routeStatus.replaceAll('_',' ')}</td><td>{item.finishedCount} of {item.stopCount} stops</td><td><button className="text-button" disabled={busy} aria-current={item.routeId === route?.routeId ? 'true' : undefined} onClick={() => { focusSelection.current = true; refresh(item.routeId); }}>View route #{item.routeId}</button></td></tr>)}</tbody></table></div><div className="account-pagination"><button className="button secondary" disabled={busy || directory.page === 0} onClick={() => refresh(undefined,directory.page-1)}>Previous routes</button><span>Page {directory.page+1} of {directory.totalPages} · {directory.totalElements} routes</span><button className="button secondary" disabled={busy || directory.page+1 >= directory.totalPages} onClick={() => refresh(undefined,directory.page+1)}>Next routes</button></div></div>}
      {route && directory && !directory.routes.some(item => item.routeId === route.routeId) && <p>Viewing route #{route.routeId} from a direct link; it is outside this directory page.</p>}
      {route && <RouteStops route={route} summaryRef={summary} busy={busy} blocked={blocked} cancelStop={cancelStop} onDetails={openDetails} onComplete={stop => { void update(stop, 'COMPLETED'); }} onCancel={setCancelStop} onConfirm={stop => { void update(stop, 'CANCELLED'); }} onKeep={() => setCancelStop(null)} />}
    </>}
    <StopDetailsDialog dialog={dialog} loading={detailLoading} error={detailError} info={info} onClose={closeDetails} onDismiss={clearDetails} />
  </section>;
}
