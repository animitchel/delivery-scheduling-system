import { useCallback, useEffect, useRef, useState } from 'react';
import { RouteStops, StopDetailsDialog } from './RouteView';
import { ApiError } from './api';
import { driverApi } from './driver-api';
import type { DriverClient, DriverRoute, Details, Recipient, Stop } from './driver-api';
import './driver.css';
type Info = { stop: Stop; delivery: Details; recipient: Recipient };
export function DriverRoutes({ client = driverApi, onAuthError, onBusyChange }: { client?: DriverClient; onAuthError: (error: ApiError) => void; onBusyChange?: (busy: boolean) => void }) {
  const [routes, setRoutes] = useState<DriverRoute[]>([]);
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
  const loadRoutes = useCallback((selected?: number) => {
    reads.current?.abort(); const controller = new AbortController(); reads.current = controller;
    const version = ++sequence.current;
    void (async () => {
      const list = await client.routes(controller.signal);
      if (selected !== undefined && !list.some(r => r.routeId === selected)) throw new ApiError(404);
      const chosen = selected ?? list.find(r => r.routeStatus === 'IN_PROGRESS')?.routeId ?? list[0]?.routeId;
      const current = chosen && list.some(r => r.routeId === chosen) ? await client.route(chosen, controller.signal) : null;
      if (!active.current || controller.signal.aborted || version !== sequence.current) return;
      setRoutes(list); setRoute(current); setBlocked(false); setLoading(false);
    })().catch((error: unknown) => {
      if (!active.current || controller.signal.aborted || version !== sequence.current) return;
      setRoutes([]); setRoute(null); setBlocked(true); setLoading(false);
      if (!auth(error)) setMessage(error instanceof ApiError && error.status === 403 ? 'Your account cannot access these assignments.' : error instanceof ApiError && error.status === 404 ? 'This assignment is no longer available. Reload your routes.' : 'Routes could not be loaded. Please try again.');
    });
  }, [client, auth]);
  function refresh(selected?: number) {
    clearDetails(); setCancelStop(null); setLoading(true); setMessage(''); setRoute(null); setRoutes([]);
    loadRoutes(selected);
  }
  useEffect(() => {
    active.current = true; loadRoutes();
    return () => { active.current = false; reads.current?.abort(); details.current?.abort(); };
  }, [loadRoutes]);
  function openDetails(stop: Stop, button: HTMLButtonElement) {
    clearDetails(); const controller = new AbortController(); details.current = controller;
    returnFocus.current = button; setDetailLoading(true);
    void client.details(stop.deliveryId, controller.signal).then(result => {
      if (!active.current || controller.signal.aborted) return;
      setInfo({ stop, ...result }); setDetailLoading(false);
    }).catch((error: unknown) => {
      if (!active.current || controller.signal.aborted) return;
      setDetailLoading(false);
      if (error instanceof ApiError && error.status === 404) setBlocked(true);
      if (!auth(error)) setDetailError(error instanceof ApiError && error.status === 404 ? 'This stop is no longer available. Reload the route.' : 'Stop details could not be loaded. Close and try again.');
    });
    dialog.current?.showModal();
  }
  function closeDetails() { clearDetails(); dialog.current?.close(); returnFocus.current?.focus(); }
  async function update(stop: Stop, status: 'COMPLETED' | 'CANCELLED') {
    if (write.current || blocked || !route || route.routeStatus !== 'IN_PROGRESS' || stop.status !== 'IN_PROGRESS') return;
    write.current = true; onBusyChange?.(true); setBusy(true); setCancelStop(null); clearDetails(); dialog.current?.close();
    const controller = new AbortController(); const timeout = window.setTimeout(() => controller.abort(), 15000);
    const selected = route.routeId;
    try {
      await client.update(stop.deliveryId, status, controller.signal);
      if (active.current) { setBlocked(true); refresh(selected); }
    } catch (error) {
      if (!active.current) return;
      setBlocked(true); setRoute(null); setRoutes([]);
      if (!auth(error)) setMessage(error instanceof ApiError && error.status === 404 ? 'This assignment is no longer available. Reload your routes.' : error instanceof ApiError && error.status === 409 ? 'This delivery has changed. Reload your routes before another action.' : 'The update outcome is uncertain. Reload your routes before another action.');
    } finally { window.clearTimeout(timeout); write.current = false; onBusyChange?.(false); if (active.current) setBusy(false); }
  }
  return <section className="driver-routes"><div className="driver-heading"><div><p className="eyebrow">Driver workspace</p><h1>Assigned routes</h1></div><button className="button secondary" disabled={busy || loading} onClick={() => refresh(route?.routeId)}>Reload routes</button></div>
    {message && <p role="alert" className="error-message">{message}</p>}
    {loading ? <p role="status">Loading your routes…</p> : routes.length === 0 && !message ? <div className="route-empty"><h2>No assigned routes</h2><p>Your routes will appear here when work is assigned.</p></div> : <>
      {routes.length > 0 && <div className="route-picker"><label htmlFor="assigned-route">Choose a route</label><select id="assigned-route" value={route?.routeId ?? ''} disabled={busy} onChange={event => refresh(Number(event.target.value))}>{routes.map(r => <option value={r.routeId} key={r.routeId}>Route #{r.routeId} · {r.scheduledDate} · {r.routeStatus.replaceAll('_',' ')}</option>)}</select></div>}
      {route && <RouteStops route={route} busy={busy} blocked={blocked} cancelStop={cancelStop} onDetails={openDetails} onComplete={stop => { void update(stop, 'COMPLETED'); }} onCancel={setCancelStop} onConfirm={stop => { void update(stop, 'CANCELLED'); }} onKeep={() => setCancelStop(null)} />}
    </>}
    <StopDetailsDialog dialog={dialog} loading={detailLoading} error={detailError} info={info} onClose={closeDetails} onDismiss={clearDetails} />
  </section>;
}
