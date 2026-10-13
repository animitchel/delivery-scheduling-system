import { api, ApiError } from './api';
import { parseDirectory } from './admin-routes-api';
import type { RoutePage, RouteSummary } from './admin-routes-api';
import { parseRoute } from './driver-api';
import type { DriverRoute } from './driver-api';
import { validDate } from './deliveries-api';
export interface GenerationResult { scheduledDate: string; routes: RouteSummary[]; processingTimeMs: number }
export interface GenerationClient {
  generate(date: string, signal: AbortSignal): Promise<GenerationResult>;
  directory(page: number, date: string, signal: AbortSignal): Promise<RoutePage>;
  route(id: number, signal: AbortSignal): Promise<DriverRoute>;
}
const object = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value);
const natural = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0;
const id = (value: unknown): value is number => natural(value) && value > 0 && value <= 2147483647;
export function generationTimeout(value: string | undefined): number {
  if (value === undefined) return 180000;
  if (!/^[1-9][0-9]*$/.test(value) || !Number.isSafeInteger(Number(value)) || Number(value) < 1000 || Number(value) > 600000) throw new Error('Route generation timeout configuration is invalid.');
  return Number(value);
}
export function parseGeneration(value: unknown, date: string): GenerationResult {
  if (!object(value) || value['scheduledDate'] !== date || !natural(value['processingTimeMs']) || !Array.isArray(value['routes']) || value['routes'].length === 0 || value['routes'].length > 500) throw new ApiError(502);
  const routes = value['routes'].map((route: unknown): RouteSummary => {
    if (!object(route) || !id(route['routeId']) || !id(route['operatorId']) || typeof route['operatorName'] !== 'string' || !route['operatorName'] || route['scheduledDate'] !== date || route['routeStatus'] !== 'IN_PROGRESS' || !natural(route['stopCount']) || route['stopCount'] < 1 || route['stopCount'] > 500 || route['finishedCount'] !== 0) throw new ApiError(502);
    return { routeId: route['routeId'], operatorId: route['operatorId'], operatorName: route['operatorName'], scheduledDate: date, routeStatus: 'IN_PROGRESS', stopCount: route['stopCount'], finishedCount: 0 };
  });
  if (new Set(routes.map(route => route.routeId)).size !== routes.length || new Set(routes.map(route => route.operatorId)).size !== routes.length || routes.reduce((sum, route) => sum + route.stopCount, 0) > 500) throw new ApiError(502);
  return { scheduledDate: date, routes, processingTimeMs: value['processingTimeMs'] };
}
export function createGenerationClient(request = api.request): GenerationClient {
  const base = '/api/admin/routes';
  async function json(response: Response): Promise<unknown> { try { return await response.json(); } catch { throw new ApiError(502); } }
  return {
    async generate(date, signal) {
      if (!validDate(date)) throw new ApiError(400);
      const response = await request(`${base}/generate`, { method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ scheduledDate: date }) });
      if (response.status !== 201) throw new ApiError(502);
      return parseGeneration(await json(response), date);
    },
    async directory(page, date, signal) {
      if (!natural(page) || page > 10000 || date && !validDate(date)) throw new ApiError(400);
      const query = new URLSearchParams({ page: String(page), size: '20' }); if (date) query.set('scheduledDate', date);
      const result = parseDirectory(await json(await request(`${base}?${query}`, { signal })), page);
      if (result.routes.some(route => !id(route.routeId) || !id(route.operatorId) || !validDate(route.scheduledDate) || date && route.scheduledDate !== date)) throw new ApiError(502);
      return result;
    },
    async route(value, signal) { if (!id(value)) throw new ApiError(400); const route = parseRoute(await json(await request(`${base}/${value}`, { signal }))); if (route.routeId !== value || !validDate(route.scheduledDate)) throw new ApiError(502); return route; },
  };
}
export const generationApi = createGenerationClient();
