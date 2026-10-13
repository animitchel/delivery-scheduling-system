import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DriverRoutes } from './DriverRoutes';
import { ApiError } from './api';
import type { DriverClient, DriverRoute, Details, Recipient } from './driver-api';
const route: DriverRoute = { routeId: 1, operatorId: 1, operatorName: 'Driver', scheduledDate: '2026-10-13', routeStatus: 'IN_PROGRESS', deliveries: [{ deliveryId: 10, recipientId: 1, clientName: '<img src=x onerror=alert(1)>', status: 'IN_PROGRESS', fromPrevDistanceKm: 2.5, fromPrevDurationText: null, fromPrevDurationSeconds: null }] };
const information: { delivery: Details; recipient: Recipient } = { delivery: { deliveryId: 10, deliveryStatus: 'IN_PROGRESS', scheduledDate: '2026-10-13', startTimeWindow: '09:00:00', endTimeWindow: '12:00:00', specialInstructions: 'Use side entrance', priorityLevel: 'HIGH' }, recipient: { firstName: 'Alex', lastName: 'Smith', address: 'Private address', contactNumber: null } };
function clientFor() { return { routes: vi.fn<DriverClient['routes']>().mockResolvedValue([route]), route: vi.fn<DriverClient['route']>().mockResolvedValue(route), details: vi.fn<DriverClient['details']>().mockResolvedValue(information), update: vi.fn<DriverClient['update']>().mockResolvedValue(undefined) }; }
function pending<T>() { let resolve!: (v: T) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; }
async function mount(client = clientFor(), auth = vi.fn(), busy = vi.fn()) { const view = render(<DriverRoutes client={client} onAuthError={auth} onBusyChange={busy} />); await screen.findByRole('button', { name: 'Stop details' }); return view; }
describe('driver workflow', () => {
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
    expect(screen.queryByText('Obsolete address')).not.toBeInTheDocument(); expect(client.details.mock.calls[0]?.[1].aborted).toBe(true);
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
    expect(screen.queryByRole('button', { name: 'Mark completed' })).not.toBeInTheDocument(); expect(client.update).toHaveBeenCalledTimes(1); expect(client.routes).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: 'Reload routes' })); await screen.findByRole('button', { name: 'Mark completed' }); expect(client.routes).toHaveBeenCalledTimes(2);
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
    const client = clientFor(); client.routes.mockRejectedValueOnce(new ApiError(500)).mockResolvedValue([]);
    render(<DriverRoutes client={client} onAuthError={vi.fn()} />); await screen.findByRole('alert'); await userEvent.click(screen.getByRole('button', { name: 'Reload routes' }));
    await screen.findByRole('heading', { name: 'No assigned routes' }); expect(client.route).not.toHaveBeenCalled();
  });
  it('switches assignments and aborts pending reads when leaving', async () => {
    const old = pending<DriverRoute>(); const second = { ...route, routeId: 2, operatorName: 'Second route' }; const client = clientFor();
    client.routes.mockResolvedValue([route, second]); const view = await mount(client); client.route.mockReturnValueOnce(old.promise);
    await userEvent.selectOptions(screen.getByLabelText('Choose a route'), '2');
    await act(async () => { old.resolve(second); await old.promise; }); await screen.findByText('2026-10-13 · Second route');
    const newer = pending<DriverRoute>(); client.route.mockReturnValueOnce(newer.promise);
    await userEvent.selectOptions(screen.getByLabelText('Choose a route'), '1');
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
    const signal = client.route.mock.calls.at(-1)?.[1]; view.unmount(); expect(signal?.aborted).toBe(true);
    await act(async () => { newer.resolve(route); await newer.promise; }); expect(screen.queryByText('Driver')).not.toBeInTheDocument();
  });
});
