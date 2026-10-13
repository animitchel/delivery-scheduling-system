import { describe, expect, it, vi } from 'vitest';
import { createDriverClient, parseRoute } from './driver-api';
import { ApiError } from './api';
import type { createApiClient } from './api';
const route = { routeId: 1, operatorId: 1, operatorName: 'Driver', scheduledDate: '2026-10-13', routeStatus: 'IN_PROGRESS', deliveries: [{ deliveryId: 10, recipientId: 1, clientName: 'Alex', status: 'IN_PROGRESS', fromPrevDistanceKm: null, fromPrevDurationText: null, fromPrevDurationSeconds: null }] };
const delivery = { deliveryId: 10, deliveryStatus: 'COMPLETED', scheduledDate: null, startTimeWindow: null, endTimeWindow: null, specialInstructions: null, priorityLevel: 'MEDIUM' };
const json = (v: unknown) => new Response(JSON.stringify(v));
type Request = ReturnType<typeof createApiClient>['request'];
describe('driver API contracts', () => {
  it('validates lists, selected route IDs and duplicate stops', async () => {
    const request = vi.fn<Request>().mockResolvedValueOnce(json([route])).mockResolvedValueOnce(json({ ...route, routeId: 2 }));
    const client = createDriverClient(request); expect(await client.routes(new AbortController().signal)).toEqual([route]);
    await expect(client.route(1, new AbortController().signal)).rejects.toMatchObject({ status: 502 });
    expect(() => parseRoute({ ...route, deliveries: [...route.deliveries, ...route.deliveries] })).toThrow(ApiError);
  });
  it.each([null, { ...route, routeId: '1' }, { ...route, routeStatus: 'INVALID' }, { ...route, routeStatus: ['IN_PROGRESS'] }, { ...route, deliveries: [{ ...route.deliveries[0], status: 'INVALID' }] }])('rejects malformed assignments', v => { expect(() => parseRoute(v)).toThrow(ApiError); });
  it('uses the ownership endpoints for on-demand delivery and recipient reads with one signal', async () => {
    const request = vi.fn<Request>().mockResolvedValueOnce(json(delivery)).mockResolvedValueOnce(json({ firstName: 'Alex', lastName: 'Smith', address: 'Street', contactNumber: null }));
    const signal = new AbortController().signal; const result = await createDriverClient(request).details(10, signal);
    expect(result.recipient.address).toBe('Street'); expect(request.mock.calls).toEqual([['/api/driver/deliveries/10', { signal }], ['/api/driver/deliveries/10/recipient', { signal }]]);
  });
  it('sends only the terminal outcome and never retries a failed write', async () => {
    const request = vi.fn<Request>().mockRejectedValue(new ApiError(409)); const signal = new AbortController().signal;
    await expect(createDriverClient(request).update(10, 'COMPLETED', signal)).rejects.toMatchObject({ status: 409 });
    expect(request).toHaveBeenCalledTimes(1); expect(request).toHaveBeenCalledWith('/api/driver/deliveries/10/status', { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: '{"deliveryStatus":"COMPLETED"}', signal });
  });
  it('treats malformed or mismatched write acknowledgements as uncertain', async () => {
    const request = vi.fn<Request>().mockResolvedValueOnce(json({ ...delivery, deliveryId: 11 })).mockResolvedValueOnce(new Response('broken'));
    const client = createDriverClient(request); await expect(client.update(10, 'COMPLETED', new AbortController().signal)).rejects.toMatchObject({ status: 502 });
    await expect(client.update(10, 'COMPLETED', new AbortController().signal)).rejects.toMatchObject({ status: 502 });
  });
  it('rejects non-positive IDs before making requests', async () => { const request = vi.fn<Request>(); await expect(createDriverClient(request).route(-1, new AbortController().signal)).rejects.toMatchObject({ status: 400 }); expect(request).not.toHaveBeenCalled(); });
});
