import { api, ApiError } from './api';
import { parseRoute, parseDetails } from './driver-api';
import type { DriverRoute as RouteDetails, Details, Recipient, Stop } from './driver-api';
export interface RouteSummary { routeId: number; operatorId: number; operatorName: string; scheduledDate: string; routeStatus: RouteDetails['routeStatus']; stopCount: number; finishedCount: number }
export interface RoutePage { routes: RouteSummary[]; page: number; size: number; totalElements: number; totalPages: number }
export interface AdminRoutesClient {
  directory(page: number, signal: AbortSignal): Promise<RoutePage>;
  route(id: number, signal: AbortSignal): Promise<RouteDetails>;
  details(stop: Stop, operatorId: number, signal: AbortSignal): Promise<{ delivery: Details; recipient: Recipient }>;
  update(id: number, status: 'COMPLETED' | 'CANCELLED', signal: AbortSignal): Promise<void>;
}
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const natural = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 0;
function id(value: number) { if (!natural(value) || value === 0) throw new ApiError(400); return value; }
export function parseDirectory(v: unknown, expected: number): RoutePage {
  if (!object(v) || v['page'] !== expected || v['size'] !== 20 || !natural(v['totalElements']) || !natural(v['totalPages'])
    || v['totalPages'] !== Math.ceil(v['totalElements']/20) || !Array.isArray(v['routes']) || v['routes'].length > 20) throw new ApiError(502);
  const routes = v['routes'].map((item: unknown): RouteSummary => {
    if (!object(item) || !natural(item['routeId']) || item['routeId'] === 0 || !natural(item['operatorId']) || item['operatorId'] === 0
      || typeof item['operatorName'] !== 'string' || typeof item['scheduledDate'] !== 'string' || !natural(item['stopCount']) || !natural(item['finishedCount'])
      || item['finishedCount'] > item['stopCount'] || !(item['routeStatus'] === 'PLANNED' || item['routeStatus'] === 'IN_PROGRESS' || item['routeStatus'] === 'COMPLETED')) throw new ApiError(502);
    return { routeId: item['routeId'], operatorId: item['operatorId'], operatorName: item['operatorName'], scheduledDate: item['scheduledDate'], routeStatus: item['routeStatus'], stopCount: item['stopCount'], finishedCount: item['finishedCount'] };
  });
  if (new Set(routes.map(r => r.routeId)).size !== routes.length) throw new ApiError(502);
  return { routes, page: expected, size: 20, totalElements: v['totalElements'], totalPages: v['totalPages'] };
}
export function createAdminRoutesClient(request = api.request): AdminRoutesClient {
  const base = '/api/v1/scheduling-app';
  async function json(path: string, signal: AbortSignal): Promise<unknown> { const response = await request(path, { signal }); try { return await response.json(); } catch { throw new ApiError(502); } }
  return {
    async directory(page, signal) { if (!natural(page) || page > 10000) throw new ApiError(400); return parseDirectory(await json(`${base}/routes/directory?page=${page}&size=20`, signal), page); },
    async route(value, signal) { const result = parseRoute(await json(`${base}/routes/${id(value)}`, signal)); if (result.routeId !== value) throw new ApiError(502); return result; },
    async details(stop, operatorId, signal) {
      const [delivery, recipient] = await Promise.all([json(`${base}/deliveries/${id(stop.deliveryId)}`, signal), json(`${base}/recipients/${id(stop.recipientId)}`, signal)]);
      const parsed = parseDetails(delivery, stop.deliveryId);
      if (!object(delivery) || delivery['recipientId'] !== stop.recipientId || delivery['operatorId'] !== operatorId || parsed.deliveryStatus !== stop.status) throw new ApiError(409);
      if (!object(recipient) || recipient['recipientId'] !== stop.recipientId || typeof recipient['firstName'] !== 'string' || typeof recipient['lastName'] !== 'string' || typeof recipient['address'] !== 'string'
        || !(recipient['contactNumber'] === null || typeof recipient['contactNumber'] === 'string')) throw new ApiError(502);
      return { delivery: parsed, recipient: { firstName: recipient['firstName'], lastName: recipient['lastName'], address: recipient['address'], contactNumber: recipient['contactNumber'] } };
    },
    async update(value, status, signal) {
      const response = await request(`${base}/deliveries/${id(value)}`, { method: 'PATCH', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ deliveryStatus: status }) });
      let data: unknown; try { data = await response.json(); } catch { throw new ApiError(502); }
      if (parseDetails(data, value).deliveryStatus !== status) throw new ApiError(502);
    },
  };
}
export const adminRoutesApi = createAdminRoutesClient();
