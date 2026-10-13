import { api, ApiError } from './api';
export type Availability = 'AVAILABLE' | 'UNAVAILABLE' | 'OFF_DUTY' | 'ON_DELIVERY';
export interface OperatorInput { name: string; phoneNumber: string; email: string; vehicleType: string | null; maxCapacity: number; warehouseId: number; availabilityStatus: Availability }
export interface Operator { operatorId: number; name: string; phoneNumber: string; email: string; vehicleType: string | null; maxCapacity: number | null; warehouseId: number | null; availabilityStatus: Availability }
export interface OperatorVersion { operator: Operator; etag: string }
export interface OperatorPage { operators: Operator[]; page: number; size: number; totalElements: number; totalPages: number }
export interface OperatorsClient {
  directory(page: number, search: string, signal: AbortSignal): Promise<OperatorPage>;
  read(id: number, signal: AbortSignal): Promise<OperatorVersion>;
  create(input: OperatorInput, signal: AbortSignal): Promise<OperatorVersion>;
  update(id: number, etag: string, input: OperatorInput, signal: AbortSignal): Promise<OperatorVersion>;
  remove(id: number, etag: string, signal: AbortSignal): Promise<void>;
}
const object = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value);
const natural = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0;
const identifier = (value: unknown): value is number => natural(value) && value > 0 && value <= 2147483647;
const availability = (value: unknown): value is Availability => typeof value === 'string' && ['AVAILABLE', 'UNAVAILABLE', 'OFF_DUTY', 'ON_DELIVERY'].includes(value);
export function validOperator(input: OperatorInput) { return !!input.name.trim() && input.name.length <= 100 && input.phoneNumber.length <= 20 && /^\+?[0-9]{10,15}$/.test(input.phoneNumber.trim()) && input.email.length <= 100 && /^[^\s@]+@[^\s@]+$/.test(input.email.trim()) && (input.vehicleType === null || input.vehicleType.length <= 50) && Number.isFinite(input.maxCapacity) && input.maxCapacity >= 0 && identifier(input.warehouseId) && availability(input.availabilityStatus); }
function tag(value: string | null) { if (!value || !/^"[a-f0-9]{64}"$/.test(value)) throw new ApiError(502); return value; }
export function parseOperator(value: unknown): Operator {
  if (!object(value) || !identifier(value['operatorId']) || typeof value['name'] !== 'string' || typeof value['phoneNumber'] !== 'string' || typeof value['email'] !== 'string' || !(value['vehicleType'] === null || typeof value['vehicleType'] === 'string') || !(value['maxCapacity'] === null || typeof value['maxCapacity'] === 'number' && Number.isFinite(value['maxCapacity'])) || !(value['warehouseId'] === null || identifier(value['warehouseId'])) || !availability(value['availabilityStatus'])) throw new ApiError(502);
  return { operatorId: value['operatorId'], name: value['name'], phoneNumber: value['phoneNumber'], email: value['email'], vehicleType: value['vehicleType'], maxCapacity: value['maxCapacity'], warehouseId: value['warehouseId'], availabilityStatus: value['availabilityStatus'] };
}
export function createOperatorsClient(request = api.request): OperatorsClient {
  const base = '/api/admin/operators';
  async function json(response: Response): Promise<unknown> { try { return await response.json(); } catch { throw new ApiError(502); } }
  async function version(response: Response, status: number, expected?: number, input?: OperatorInput) {
    if (response.status !== status) throw new ApiError(502); const operator = parseOperator(await json(response));
    if (expected !== undefined && operator.operatorId !== expected || input && (operator.name !== input.name.trim() || operator.phoneNumber !== input.phoneNumber.trim() || operator.email !== input.email.trim() || operator.vehicleType !== (input.vehicleType?.trim() || null) || operator.maxCapacity !== input.maxCapacity || operator.warehouseId !== input.warehouseId || operator.availabilityStatus !== input.availabilityStatus)) throw new ApiError(502);
    return { operator, etag: tag(response.headers.get('ETag')) };
  }
  function id(value: number) { if (!identifier(value)) throw new ApiError(400); return value; }
  function body(input: OperatorInput) { if (!validOperator(input)) throw new ApiError(400); return JSON.stringify({ name: input.name.trim(), phoneNumber: input.phoneNumber.trim(), email: input.email.trim(), vehicleType: input.vehicleType?.trim() || null, maxCapacity: input.maxCapacity, warehouseId: input.warehouseId, availabilityStatus: input.availabilityStatus }); }
  return {
    async directory(page, search, signal) {
      if (!natural(page) || page > 10000 || search.length > 100) throw new ApiError(400); const response = await request(`${base}?${new URLSearchParams({ page: String(page), size: '20', search })}`, { signal }); if (response.status !== 200) throw new ApiError(502); const value = await json(response);
      if (!object(value) || value['page'] !== page || value['size'] !== 20 || !natural(value['totalElements']) || !natural(value['totalPages']) || value['totalPages'] !== Math.ceil(value['totalElements'] / 20) || !Array.isArray(value['operators']) || value['operators'].length > 20) throw new ApiError(502);
      const operators = value['operators'].map(parseOperator); if (new Set(operators.map(o => o.operatorId)).size !== operators.length) throw new ApiError(502); return { operators, page, size: 20, totalElements: value['totalElements'], totalPages: value['totalPages'] };
    },
    async read(value, signal) { return version(await request(`${base}/${id(value)}`, { signal }), 200, value); },
    async create(input, signal) { if (input.availabilityStatus === 'ON_DELIVERY') throw new ApiError(400); return version(await request(base, { method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: body(input) }), 201, undefined, input); },
    async update(value, etag, input, signal) { tag(etag); return version(await request(`${base}/${id(value)}`, { method: 'PUT', signal, headers: { 'Content-Type': 'application/json', 'If-Match': etag }, body: body(input) }), 200, value, input); },
    async remove(value, etag, signal) { tag(etag); if ((await request(`${base}/${id(value)}`, { method: 'DELETE', signal, headers: { 'If-Match': etag } })).status !== 204) throw new ApiError(502); },
  };
}
export const operatorsApi = createOperatorsClient();
