import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AdminRoutes } from './AdminRoutes';
import { ApiError } from './api';
import type { DriverRoute, Details, Recipient } from './driver-api';
import type { AdminRoutesClient, RoutePage } from './admin-routes-api';
const route: DriverRoute = { routeId: 1, operatorId: 1, operatorName: 'Driver', scheduledDate: '2026-10-13', routeStatus: 'IN_PROGRESS', deliveries: [{ deliveryId: 10, recipientId: 1, clientName: '<img src=x onerror=alert(1)>', status: 'IN_PROGRESS', fromPrevDistanceKm: 2.5, fromPrevDurationText: null, fromPrevDurationSeconds: null }] };
const information: { delivery: Details; recipient: Recipient } = { delivery: { deliveryId: 10, deliveryStatus: 'IN_PROGRESS', scheduledDate: '2026-10-13', startTimeWindow: '09:00:00', endTimeWindow: '12:00:00', specialInstructions: 'Use side entrance', priorityLevel: 'HIGH' }, recipient: { firstName: 'Alex', lastName: 'Smith', address: 'Private address', contactNumber: null } };
function directory(routes: DriverRoute[]): RoutePage { return {routes:routes.map(r=>({routeId:r.routeId,operatorId:r.operatorId,operatorName:r.operatorName,scheduledDate:r.scheduledDate,routeStatus:r.routeStatus,stopCount:r.deliveries.length,finishedCount:r.deliveries.filter(s=>s.status==='COMPLETED'||s.status==='CANCELLED').length})),page:0,size:20,totalElements:routes.length,totalPages:routes.length?1:0}; }
function clientFor() { return { directory: vi.fn<AdminRoutesClient['directory']>().mockResolvedValue(directory([route])), route: vi.fn<AdminRoutesClient['route']>().mockResolvedValue(route), details: vi.fn<AdminRoutesClient['details']>().mockResolvedValue(information), update: vi.fn<AdminRoutesClient['update']>().mockResolvedValue(undefined) }; }
function pending<T>() { let resolve!: (v: T) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; }
async function mount(client = clientFor(), auth = vi.fn(), busy = vi.fn()) { const view = render(<AdminRoutes client={client} onAuthError={auth} onBusyChange={busy} />); await screen.findByRole('button', { name: 'Stop details' }); return view; }
describe('administrator route workflow', () => {
  it('shows ordered assignments and fallback estimates without loading private details', async () => {
    const client = clientFor(); await mount(client);
    expect(screen.getByRole('status')).toHaveTextContent('0 of 1 stops finished');
    expect(screen.getByText('Travel time unavailable · 2.5 km')).toBeVisible();
    expect(client.details).not.toHaveBeenCalled(); expect(document.querySelector('img')).toBeNull();
  });
  it('loads recipient, window and instructions on demand and clears them on close', async () => {
    const client = clientFor(); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Stop details' }));
    expect(await screen.findByText('Private address')).toBeVisible(); expect(screen.getByText('09:00 – 12:00')).toBeVisible();
    expect(screen.getByText('Use side entrance')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Close details' }));
    expect(screen.queryByText('Private address')).not.toBeInTheDocument(); expect(screen.getByRole('button', { name: 'Stop details' })).toHaveFocus();
  });
  it('ignores an obsolete private response after closing and reopening the dialog', async () => {
    const old = pending<typeof information>(); const client = clientFor(); client.details.mockReturnValueOnce(old.promise);
    await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Stop details' }));
    await userEvent.click(screen.getByRole('button', { name: 'Close details' }));
    await userEvent.click(screen.getByRole('button', { name: 'Stop details' })); await screen.findByText('Private address');
    await act(async () => { old.resolve({ ...information, recipient: { ...information.recipient, address: 'Obsolete address' } }); await old.promise; });
    expect(screen.queryByText('Obsolete address')).not.toBeInTheDocument(); expect(client.details.mock.calls[0]?.[2].aborted).toBe(true);
  });
  it('prevents duplicate writes and reloads authoritative progress after completion', async () => {
    const write = pending<void>(); const client = clientFor(); client.update.mockReturnValue(write.promise); const busy = vi.fn();
    await mount(client, vi.fn(), busy); const button = screen.getByRole('button', { name: 'Mark completed' }); fireEvent.click(button); fireEvent.click(button);
    expect(client.update).toHaveBeenCalledTimes(1); expect(button).toBeDisabled(); expect(screen.getByRole('button', { name: 'Reload routes' })).toBeDisabled();
    client.route.mockResolvedValue({ ...route, routeStatus: 'COMPLETED', deliveries: [{ ...route.deliveries[0]!, status: 'COMPLETED' }] });
    await act(async () => { write.resolve(); await write.promise; });
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('1 of 1 stops finished'));
    expect(screen.getByRole('button', { name: 'Mark completed' })).toBeDisabled(); expect(busy.mock.calls).toEqual([[true], [false]]);
  });
  it('bounds a hanging update and requires reload without retrying', async () => {
    const client = clientFor(); client.update.mockImplementation((_id, _status, signal) => new Promise((_resolve, reject) => { signal.addEventListener('abort', () => reject(new DOMException('Cancelled', 'AbortError'))); }));
    await mount(client); vi.useFakeTimers();
    try {
      fireEvent.click(screen.getByRole('button', { name: 'Mark completed' }));
      await act(async () => { await vi.advanceTimersByTimeAsync(15000); });
      expect(screen.getByRole('alert')).toHaveTextContent('outcome is uncertain'); expect(client.update).toHaveBeenCalledTimes(1);
      expect(screen.queryByRole('button', { name: 'Mark completed' })).not.toBeInTheDocument();
    } finally { vi.useRealTimers(); }
  });
  it('requires confirmation for cancellation and lets the driver keep the delivery', async () => {
    const client = clientFor(); await mount(client); await userEvent.click(screen.getByRole('button', { name: 'Cancel delivery' }));
    expect(client.update).not.toHaveBeenCalled(); await userEvent.click(screen.getByRole('button', { name: 'Keep delivery' }));
    expect(screen.queryByRole('group')).not.toBeInTheDocument(); await userEvent.click(screen.getByRole('button', { name: 'Cancel delivery' }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm cancellation' })); expect(client.update).toHaveBeenCalledWith(10, 'CANCELLED', expect.any(AbortSignal));
  });
  it.each([404,409,500])('clears stale state after update error %s and requires explicit reload', async code => {
    const client = clientFor(); client.update.mockRejectedValueOnce(new ApiError(code)); await mount(client);
    await userEvent.click(screen.getByRole('button', { name: 'Mark completed' })); await screen.findByRole('alert');
    expect(screen.queryByRole('button', { name: 'Mark completed' })).not.toBeInTheDocument(); expect(client.update).toHaveBeenCalledTimes(1); expect(client.directory).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: 'Reload routes' })); await screen.findByRole('button', { name: 'Mark completed' }); expect(client.directory).toHaveBeenCalledTimes(2);
  });
  it.each([new ApiError(401), new ApiError(403, true)])('hands session expiry/setup to the shell', async error => {
    const auth = vi.fn(); const client = clientFor(); client.update.mockRejectedValue(error); await mount(client, auth);
    await userEvent.click(screen.getByRole('button', { name: 'Mark completed' })); await waitFor(() => expect(auth).toHaveBeenCalledWith(error));
  });
  it('blocks mutations when an on-demand stop is no longer assigned', async () => {
    const client = clientFor(); client.details.mockRejectedValue(new ApiError(404)); await mount(client);
    await userEvent.click(screen.getByRole('button', { name: 'Stop details' })); expect(await screen.findByRole('alert')).toHaveTextContent('no longer available');
    await userEvent.click(screen.getByRole('button', { name: 'Close details' })); expect(screen.getByRole('button', { name: 'Mark completed' })).toBeDisabled();
  });
  it('shows empty assignments and recovers from read failure', async () => {
    const client = clientFor(); client.directory.mockRejectedValueOnce(new ApiError(500)).mockResolvedValue(directory([]));
    render(<AdminRoutes client={client} onAuthError={vi.fn()} />); await screen.findByRole('alert'); await userEvent.click(screen.getByRole('button', { name: 'Reload routes' }));
    await screen.findByRole('heading', { name: 'No routes found' }); expect(client.route).not.toHaveBeenCalled();
  });
  it('recovers when the last directory page disappears', async () => {
    const client=clientFor(); client.directory.mockResolvedValueOnce({...directory([route]),totalElements:21,totalPages:2}); await mount(client);
    client.directory.mockResolvedValueOnce({...directory([]),page:1,totalElements:1,totalPages:1}).mockResolvedValueOnce(directory([route]));
    await userEvent.click(screen.getByRole('button',{name:'Next routes'})); await screen.findByRole('button',{name:'View route #1'});
    expect(client.directory.mock.calls.map(call=>call[0])).toEqual([0,1,0]);
  });
  it('bounds stalled directory reads and allows explicit reload', async () => {
    const client=clientFor(); client.directory.mockImplementation((_page,signal)=>new Promise((_resolve,reject)=>{signal.addEventListener('abort',()=>reject(new DOMException('Cancelled','AbortError')));})); vi.useFakeTimers();
    try { render(<AdminRoutes client={client} onAuthError={vi.fn()} />); await act(async()=>{await vi.advanceTimersByTimeAsync(30000);}); expect(screen.getByRole('alert')).toHaveTextContent('Routes could not be loaded'); expect(screen.getByRole('button',{name:'Reload routes'})).toBeEnabled(); } finally { vi.useRealTimers(); }
  });
  it('bounds stalled stop details without retaining private data', async () => {
    const client=clientFor(); client.details.mockImplementation((_stop,_operator,signal)=>new Promise((_resolve,reject)=>{signal.addEventListener('abort',()=>reject(new DOMException('Cancelled','AbortError')));})); await mount(client); vi.useFakeTimers();
    try { fireEvent.click(screen.getByRole('button',{name:'Stop details'})); await act(async()=>{await vi.advanceTimersByTimeAsync(30000);}); expect(screen.getByRole('alert')).toHaveTextContent('Stop details could not be loaded'); expect(screen.queryByText('Private address')).not.toBeInTheDocument(); } finally { vi.useRealTimers(); }
  });
  it('switches assignments and aborts pending reads when leaving', async () => {
    const old = pending<DriverRoute>(); const second = { ...route, routeId: 2, operatorName: 'Second route' }; const client = clientFor();
    client.directory.mockResolvedValue(directory([route, second])); const view = await mount(client); client.route.mockReturnValueOnce(old.promise);
    await userEvent.click(screen.getByRole('button',{name:'View route #2'}));
    await act(async () => { old.resolve(second); await old.promise; }); await screen.findByText('2026-10-13 · Second route');
    expect(screen.getByRole('region',{name:'Route #2 overview'})).toHaveFocus();
    const newer = pending<DriverRoute>(); client.route.mockReturnValueOnce(newer.promise);
    await userEvent.click(screen.getByRole('button',{name:'View route #1'}));
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
    const signal = client.route.mock.calls.at(-1)?.[1]; view.unmount(); expect(signal?.aborted).toBe(true);
    await act(async () => { newer.resolve(route); await newer.promise; }); expect(screen.queryByText('Driver')).not.toBeInTheDocument();
  });
  it('loads a directly linked route outside the first directory page and focuses its summary', async () => {
    const client=clientFor(); client.directory.mockResolvedValue(directory([{...route,routeId:2}]));client.route.mockResolvedValue({...route,routeId:91});
    render(<AdminRoutes client={client} initialRouteId={91} onAuthError={vi.fn()} />); await screen.findByRole('heading',{name:'Route #91'});
    expect(client.route).toHaveBeenCalledWith(91,expect.any(AbortSignal));await waitFor(() => expect(screen.getByRole('region',{name:'Route #91 overview'})).toHaveFocus());expect(screen.getByText(/outside this directory page/)).toBeVisible();
  });
  it('does not substitute another route when the deep-link target is missing', async () => {
    const client=clientFor();client.route.mockRejectedValue(new ApiError(404));render(<AdminRoutes client={client} initialRouteId={91} onAuthError={vi.fn()} />);
    await screen.findByRole('alert');expect(client.route).toHaveBeenCalledTimes(1);expect(client.route.mock.calls[0]?.[0]).toBe(91);expect(screen.queryByRole('heading',{name:'Route #1'})).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button',{name:'Reload routes'}));await screen.findByRole('alert');expect(client.route.mock.calls.map(call=>call[0])).toEqual([91,91]);
  });

});
