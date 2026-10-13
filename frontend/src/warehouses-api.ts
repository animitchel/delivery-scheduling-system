import { api, ApiError } from './api';
export interface WarehouseInput { name: string; address: string; latitude: number; longitude: number }
export interface Warehouse extends WarehouseInput { warehouseId: number }
export interface WarehouseVersion { warehouse: Warehouse; etag: string }
export interface WarehousePage { warehouses: Warehouse[]; page: number; size: number; totalElements: number; totalPages: number }
export interface WarehousesClient {
  directory(page: number, search: string, signal: AbortSignal): Promise<WarehousePage>;
  read(id: number, signal: AbortSignal): Promise<WarehouseVersion>;
  create(input: WarehouseInput, signal: AbortSignal): Promise<WarehouseVersion>;
  update(id: number, etag: string, input: WarehouseInput, signal: AbortSignal): Promise<WarehouseVersion>;
  remove(id: number, etag: string, signal: AbortSignal): Promise<void>;
}
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v);
const natural = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 0;
const identifier = (v: unknown): v is number => natural(v) && v > 0 && v <= 2147483647;
export function coordinate(value: unknown, bound: number): value is number {
  if (typeof value !== 'number' || !Number.isFinite(value) || Math.abs(value) > bound) return false;
  const text = String(value); return !text.includes('e') && (text.split('.')[1]?.length ?? 0) <= 6;
}
export function validWarehouse(input: WarehouseInput) { return !!input.name.trim() && input.name.length <= 255 && !!input.address.trim() && input.address.length <= 1000 && coordinate(input.latitude, 90) && coordinate(input.longitude, 180); }
function tag(value: string | null) { if (!value || !/^"[a-f0-9]{64}"$/.test(value)) throw new ApiError(502); return value; }
export function parseWarehouse(value: unknown): Warehouse {
  if (!object(value) || !identifier(value['warehouseId']) || typeof value['name'] !== 'string' || typeof value['address'] !== 'string' || typeof value['latitude'] !== 'number' || !Number.isFinite(value['latitude']) || typeof value['longitude'] !== 'number' || !Number.isFinite(value['longitude'])) throw new ApiError(502);
  return { warehouseId: value['warehouseId'], name: value['name'], address: value['address'], latitude: value['latitude'], longitude: value['longitude'] };
}
export function createWarehousesClient(request = api.request): WarehousesClient {
  const base = '/api/admin/warehouses';
  async function json(response: Response): Promise<unknown> { try { return await response.json(); } catch { throw new ApiError(502); } }
  async function version(response: Response, status: number, expected?: number, input?: WarehouseInput) {
    if (response.status !== status) throw new ApiError(502);
    const warehouse = parseWarehouse(await json(response)); if (expected !== undefined && warehouse.warehouseId !== expected) throw new ApiError(502);
    if (input && (!validWarehouse(warehouse) || warehouse.name !== input.name.trim() || warehouse.address !== input.address.trim() || warehouse.latitude !== input.latitude || warehouse.longitude !== input.longitude)) throw new ApiError(502);
    return { warehouse, etag: tag(response.headers.get('ETag')) };
  }
  function id(value: number) { if (!identifier(value)) throw new ApiError(400); return value; }
  function body(input: WarehouseInput) { if (!validWarehouse(input)) throw new ApiError(400); return JSON.stringify({ name: input.name.trim(), address: input.address.trim(), latitude: input.latitude, longitude: input.longitude }); }
  return {
    async directory(page, search, signal) {
      if (!natural(page) || page > 10000 || search.length > 100) throw new ApiError(400);
      const response = await request(`${base}?${new URLSearchParams({ page: String(page), size: '20', search })}`, { signal }); if (response.status !== 200) throw new ApiError(502);
      const value = await json(response);
      if (!object(value) || value['page'] !== page || value['size'] !== 20 || !natural(value['totalElements']) || !natural(value['totalPages']) || value['totalPages'] !== Math.ceil(value['totalElements'] / 20) || !Array.isArray(value['warehouses']) || value['warehouses'].length > 20) throw new ApiError(502);
      const warehouses = value['warehouses'].map(parseWarehouse); if (new Set(warehouses.map(w => w.warehouseId)).size !== warehouses.length) throw new ApiError(502);
      return { warehouses, page, size: 20, totalElements: value['totalElements'], totalPages: value['totalPages'] };
    },
    async read(value, signal) { return version(await request(`${base}/${id(value)}`, { signal }), 200, value); },
    async create(input, signal) { return version(await request(base, { method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: body(input) }), 201, undefined, input); },
    async update(value, etag, input, signal) { tag(etag); return version(await request(`${base}/${id(value)}`, { method: 'PUT', signal, headers: { 'Content-Type': 'application/json', 'If-Match': etag }, body: body(input) }), 200, value, input); },
    async remove(value, etag, signal) { tag(etag); const response = await request(`${base}/${id(value)}`, { method: 'DELETE', signal, headers: { 'If-Match': etag } }); if (response.status !== 204) throw new ApiError(502); },
  };
}
export const warehousesApi = createWarehousesClient();
