import { api, ApiError } from './api';
export type DeliveryStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
export interface Stop { deliveryId: number; recipientId: number; clientName: string; status: DeliveryStatus; fromPrevDistanceKm: number | null; fromPrevDurationText: string | null; fromPrevDurationSeconds: number | null }
export interface DriverRoute { routeId: number; operatorId: number; operatorName: string; scheduledDate: string; routeStatus: 'PLANNED' | 'IN_PROGRESS' | 'COMPLETED'; deliveries: Stop[] }
export interface Details { deliveryId: number; deliveryStatus: DeliveryStatus; scheduledDate: string | null; startTimeWindow: string | null; endTimeWindow: string | null; specialInstructions: string | null; priorityLevel: 'HIGH' | 'MEDIUM' | 'LOW' }
export interface Recipient { firstName: string; lastName: string; address: string; contactNumber: string | null }
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const id = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) > 0;
const text = (v: unknown): v is string => typeof v === 'string';
const nullableText = (v: unknown): v is string | null => v === null || text(v);
const status = (v: unknown): v is DeliveryStatus => v === 'PENDING' || v === 'IN_PROGRESS' || v === 'COMPLETED' || v === 'CANCELLED';
export function parseRoute(v: unknown): DriverRoute {
  if (!object(v) || !id(v['routeId']) || !id(v['operatorId']) || !text(v['operatorName']) || !text(v['scheduledDate'])
    || !(v['routeStatus'] === 'PLANNED' || v['routeStatus'] === 'IN_PROGRESS' || v['routeStatus'] === 'COMPLETED') || !Array.isArray(v['deliveries'])) throw new ApiError(502);
  const deliveries = v['deliveries'].map((d: unknown): Stop => {
    if (!object(d) || !id(d['deliveryId']) || !id(d['recipientId']) || !text(d['clientName']) || !status(d['status'])
      || !nullableText(d['fromPrevDurationText']) || !(d['fromPrevDistanceKm'] === null || (typeof d['fromPrevDistanceKm'] === 'number' && Number.isFinite(d['fromPrevDistanceKm']) && d['fromPrevDistanceKm'] >= 0))
      || !(d['fromPrevDurationSeconds'] === null || (Number.isSafeInteger(d['fromPrevDurationSeconds']) && Number(d['fromPrevDurationSeconds']) >= 0))) throw new ApiError(502);
    return d as unknown as Stop;
  });
  if (new Set(deliveries.map(d => d.deliveryId)).size !== deliveries.length) throw new ApiError(502);
  return { routeId: v['routeId'], operatorId: v['operatorId'], operatorName: v['operatorName'], scheduledDate: v['scheduledDate'], routeStatus: v['routeStatus'] as DriverRoute['routeStatus'], deliveries };
}
export function parseDetails(v: unknown, expected: number): Details {
  if (!object(v) || v['deliveryId'] !== expected || !status(v['deliveryStatus']) || !nullableText(v['scheduledDate'])
    || !nullableText(v['startTimeWindow']) || !nullableText(v['endTimeWindow']) || !nullableText(v['specialInstructions'])
    || !(v['priorityLevel'] === 'HIGH' || v['priorityLevel'] === 'MEDIUM' || v['priorityLevel'] === 'LOW')) throw new ApiError(502);
  return v as unknown as Details;
}
export interface DriverClient { routes(signal: AbortSignal): Promise<DriverRoute[]>; route(id: number, signal: AbortSignal): Promise<DriverRoute>; details(id: number, signal: AbortSignal): Promise<{ delivery: Details; recipient: Recipient }>; update(id: number, status: 'COMPLETED' | 'CANCELLED', signal: AbortSignal): Promise<void> }
export function createDriverClient(request = api.request): DriverClient {
  async function json(path: string, signal: AbortSignal): Promise<unknown> {
    const response = await request(path, { signal });
    try { return await response.json(); } catch { throw new ApiError(502); }
  }
  const pathId = (value: number) => { if (!id(value)) throw new ApiError(400); return String(value); };
  return {
    async routes(signal) { const value = await json('/api/driver/routes', signal); if (!Array.isArray(value)) throw new ApiError(502); const routes = value.map(parseRoute); if (new Set(routes.map(r => r.routeId)).size !== routes.length) throw new ApiError(502); return routes; },
    async route(value, signal) { const result = parseRoute(await json(`/api/driver/routes/${pathId(value)}`, signal)); if (result.routeId !== value) throw new ApiError(502); return result; },
    async details(value, signal) {
      const [delivery, recipient] = await Promise.all([json(`/api/driver/deliveries/${pathId(value)}`, signal), json(`/api/driver/deliveries/${pathId(value)}/recipient`, signal)]);
      if (!object(recipient) || !text(recipient['firstName']) || !text(recipient['lastName']) || !text(recipient['address']) || !nullableText(recipient['contactNumber'])) throw new ApiError(502);
      return { delivery: parseDetails(delivery, value), recipient: recipient as unknown as Recipient };
    },
    async update(value, status, signal) {
      const response = await request(`/api/driver/deliveries/${pathId(value)}/status`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ deliveryStatus: status }), signal });
      let data: unknown; try { data = await response.json(); } catch { throw new ApiError(502); }
      if (parseDetails(data, value).deliveryStatus !== status) throw new ApiError(502);
    },
  };
}
export const driverApi = createDriverClient();
