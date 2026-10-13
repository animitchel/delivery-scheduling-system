import type { RefObject } from 'react';
import type { DriverRoute as RouteDetails, Stop, Details, Recipient } from './driver-api';
export type StopInformation = { delivery: Details; recipient: Recipient };
interface StopProps {
  route: RouteDetails; summaryRef?: RefObject<HTMLDivElement | null>; busy: boolean; blocked: boolean; cancelStop: Stop | null;
  onDetails: (stop: Stop, button: HTMLButtonElement) => void;
  onComplete: (stop: Stop) => void; onCancel: (stop: Stop) => void; onConfirm: (stop: Stop) => void; onKeep: () => void;
}
export function RouteStops({ route, summaryRef, busy, blocked, cancelStop, onDetails, onComplete, onCancel, onConfirm, onKeep }: StopProps) {
  const done = route.deliveries.filter(s => s.status === 'COMPLETED' || s.status === 'CANCELLED').length;
  return (
    <><div className="route-summary" ref={summaryRef} tabIndex={summaryRef ? -1 : undefined} role="region" aria-label={`Route #${route.routeId} overview`}><div><h2>Route #{route.routeId}</h2><p>{route.scheduledDate} · {route.operatorName}</p></div><span className="status-badge">{route.routeStatus.replaceAll('_',' ')}</span><p className="route-progress" role="status">{done} of {route.deliveries.length} stops finished</p></div>
        {route.deliveries.length === 0 && <p>No stops on this route.</p>}
        <ol className="stop-list">{route.deliveries.map((stop, index) => <li key={stop.deliveryId} className="stop-card"><div className="stop-number" aria-label={`Stop ${index + 1}`}>{index + 1}</div><div className="stop-body"><div className="stop-title"><h2>{stop.clientName}</h2><span className="status-badge">{stop.status.replaceAll('_',' ')}</span></div><p className="travel">{stop.fromPrevDurationText ?? 'Travel time unavailable'}{stop.fromPrevDistanceKm !== null ? ` · ${stop.fromPrevDistanceKm.toFixed(1)} km` : ''}</p><div className="stop-actions"><button className="button secondary" disabled={busy} onClick={event => onDetails(stop, event.currentTarget)}>Stop details</button><button className="button" disabled={busy || blocked || stop.status !== 'IN_PROGRESS' || route.routeStatus !== 'IN_PROGRESS'} onClick={() => onComplete(stop)}>Mark completed</button><button className="text-button" disabled={busy || blocked || stop.status !== 'IN_PROGRESS' || route.routeStatus !== 'IN_PROGRESS'} onClick={() => onCancel(stop)}>Cancel delivery</button></div>
          {cancelStop?.deliveryId === stop.deliveryId && <div className="cancel-confirm" role="group" aria-label="Confirm delivery cancellation"><p>Cancel this delivery? This outcome cannot be reopened here.</p><button className="button danger" disabled={busy} onClick={() => onConfirm(stop)}>Confirm cancellation</button><button className="button secondary" disabled={busy} onClick={onKeep}>Keep delivery</button></div>}
        </div></li>)}</ol></>
  );
}
export function StopDetailsDialog({ dialog, loading, error, info, onClose, onDismiss }: {
  dialog: RefObject<HTMLDialogElement | null>; loading: boolean; error: string; info: StopInformation | null;
  onClose: () => void; onDismiss: () => void;
}) {
  return (
    <dialog ref={dialog} className="stop-dialog" aria-labelledby="stop-dialog-title" onCancel={onDismiss} onClose={() => { if (!dialog.current?.open) onDismiss(); }}><div className="dialog-heading"><h2 id="stop-dialog-title">Stop details</h2><button className="button secondary" onClick={onClose}>Close details</button></div>
      {loading && <p role="status">Loading stop details…</p>}{error && <p role="alert">{error}</p>}
      {info && <><h3>{info.recipient.firstName} {info.recipient.lastName}</h3><p>{info.recipient.address}</p><p>Contact: {info.recipient.contactNumber ?? 'Not provided'}</p><dl><dt>Scheduled date</dt><dd>{info.delivery.scheduledDate ?? 'Not provided'}</dd><dt>Delivery window</dt><dd>{info.delivery.startTimeWindow?.slice(0,5) ?? 'Not provided'} – {info.delivery.endTimeWindow?.slice(0,5) ?? 'Not provided'}</dd><dt>Priority</dt><dd>{info.delivery.priorityLevel}</dd><dt>Instructions</dt><dd>{info.delivery.specialInstructions ?? 'No special instructions'}</dd><dt>Status</dt><dd>{info.delivery.deliveryStatus.replaceAll('_',' ')}</dd></dl></>}
    </dialog>
  );
}
