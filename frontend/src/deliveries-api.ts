import { api, ApiError } from './api';
export const deliveryStatuses = ['PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'] as const;
export type DeliveryStatus = typeof deliveryStatuses[number];
export type Priority = 'HIGH' | 'MEDIUM' | 'LOW';
export interface DeliveryInput { recipientId: number; scheduledDate: string; startTimeWindow: string; endTimeWindow: string; priorityLevel: Priority; specialInstructions: string | null }
export interface DeliveryFilters { scheduledDate: string; status: DeliveryStatus | ''; recipientId: number | null }
interface DeliveryCore { deliveryId: number; recipientId: number; operatorId: number | null; scheduledDate: string | null; startTimeWindow: string | null; endTimeWindow: string | null; priorityLevel: Priority; deliveryStatus: DeliveryStatus }
export interface DeliverySummary extends DeliveryCore { firstName: string; lastName: string }
export interface DeliveryDetails extends DeliveryCore { clientName: string; operatorName: string | null; specialInstructions: string | null }
export interface DeliveryPage { deliveries: DeliverySummary[]; page: number; size: number; totalElements: number; totalPages: number }
export interface DeliveriesClient {
  directory(page: number, filters: DeliveryFilters, signal: AbortSignal): Promise<DeliveryPage>;
  read(id: number, signal: AbortSignal): Promise<DeliveryDetails>;
  create(input: DeliveryInput, signal: AbortSignal): Promise<DeliveryDetails>;
}
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v);
const natural = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 0;
const identifier = (v: unknown): v is number => natural(v) && v > 0 && v <= 2147483647;
const nullableText = (v: unknown): v is string | null => v === null || typeof v === 'string';
export function validDate(v: string): boolean {
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(v) || Number(v.slice(0, 4)) < 1000) return false;
  const date = new Date(v + 'T00:00:00Z');
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === v;
}
function time(v: string, fractions = false): boolean {
  return (fractions ? /^(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\.[0-9]{1,9})?)?$/ : /^(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?$/).test(v);
}
const wholeTime = (v: string) => v.length === 5 ? v + ':00' : v;
export function inputError(input: DeliveryInput): string | null {
  if (!identifier(input.recipientId)) return 'Choose a recipient before creating a delivery.';
  if (!validDate(input.scheduledDate)) return 'Enter a valid scheduled date between years 1000 and 9999.';
  if (!time(input.startTimeWindow) || !time(input.endTimeWindow) || wholeTime(input.startTimeWindow) >= wholeTime(input.endTimeWindow)) return 'The delivery window must end after it starts on the same day, using whole seconds.';
  if (!(['HIGH', 'MEDIUM', 'LOW'] as unknown[]).includes(input.priorityLevel)) return 'Choose a delivery priority.';
  if (input.specialInstructions !== null && input.specialInstructions.length > 255) return 'Special instructions must be at most 255 characters.';
  return null;
}
function core(v: unknown): DeliveryCore {
  if (!object(v) || !identifier(v['deliveryId']) || !identifier(v['recipientId']) || !(v['operatorId'] === null || identifier(v['operatorId']))
    || !(v['scheduledDate'] === null || typeof v['scheduledDate'] === 'string' && validDate(v['scheduledDate']))
    || !(v['startTimeWindow'] === null || typeof v['startTimeWindow'] === 'string' && time(v['startTimeWindow'], true))
    || !(v['endTimeWindow'] === null || typeof v['endTimeWindow'] === 'string' && time(v['endTimeWindow'], true))
    || !(['HIGH', 'MEDIUM', 'LOW'] as unknown[]).includes(v['priorityLevel']) || !(deliveryStatuses as readonly unknown[]).includes(v['deliveryStatus'])) throw new ApiError(502);
  return { deliveryId: v['deliveryId'], recipientId: v['recipientId'], operatorId: v['operatorId'], scheduledDate: v['scheduledDate'] as string | null, startTimeWindow: v['startTimeWindow'] as string | null, endTimeWindow: v['endTimeWindow'] as string | null, priorityLevel: v['priorityLevel'] as Priority, deliveryStatus: v['deliveryStatus'] as DeliveryStatus };
}
export function parseDelivery(v: unknown, expected?: number): DeliveryDetails {
  const result = core(v);
  if (!object(v) || expected !== undefined && result.deliveryId !== expected || typeof v['clientName'] !== 'string' || !nullableText(v['operatorName']) || !nullableText(v['specialInstructions'])) throw new ApiError(502);
  return { ...result, clientName: v['clientName'], operatorName: v['operatorName'], specialInstructions: v['specialInstructions'] };
}
export function parseDeliveryPage(v: unknown, expected: number): DeliveryPage {
  if (!object(v) || v['page'] !== expected || v['size'] !== 20 || !natural(v['totalElements']) || !natural(v['totalPages']) || v['totalPages'] !== Math.ceil(v['totalElements'] / 20) || !Array.isArray(v['deliveries']) || v['deliveries'].length > 20) throw new ApiError(502);
  const deliveries = v['deliveries'].map((item: unknown): DeliverySummary => {
    const result = core(item); if (!object(item) || typeof item['firstName'] !== 'string' || typeof item['lastName'] !== 'string') throw new ApiError(502);
    return { ...result, firstName: item['firstName'], lastName: item['lastName'] };
  });
  if (new Set(deliveries.map(d => d.deliveryId)).size !== deliveries.length) throw new ApiError(502);
  return { deliveries, page: expected, size: 20, totalElements: v['totalElements'], totalPages: v['totalPages'] };
}
export function createDeliveriesClient(request = api.request): DeliveriesClient {
  const base = '/api/admin/deliveries';
  async function json(response: Response): Promise<unknown> { try { return await response.json(); } catch { throw new ApiError(502); } }
  return {
    async directory(page, filters, signal) {
      if (!natural(page) || page > 10000 || filters.scheduledDate && !validDate(filters.scheduledDate) || filters.status && !(deliveryStatuses as readonly string[]).includes(filters.status) || filters.recipientId !== null && !identifier(filters.recipientId)) throw new ApiError(400);
      const query = new URLSearchParams({ page: String(page), size: '20' });
      if (filters.scheduledDate) query.set('scheduledDate', filters.scheduledDate);
      if (filters.status) query.set('status', filters.status);
      if (filters.recipientId !== null) query.set('recipientId', String(filters.recipientId));
      return parseDeliveryPage(await json(await request(`${base}?${query}`, { signal })), page);
    },
    async read(id, signal) { if (!identifier(id)) throw new ApiError(400); return parseDelivery(await json(await request(`${base}/${id}`, { signal })), id); },
    async create(input, signal) {
      if (inputError(input)) throw new ApiError(400);
      const normalized = { recipientId: input.recipientId, scheduledDate: input.scheduledDate, startTimeWindow: input.startTimeWindow, endTimeWindow: input.endTimeWindow, priorityLevel: input.priorityLevel, specialInstructions: input.specialInstructions?.trim() || null };
      const response = await request(base, { method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(normalized) });
      const result = parseDelivery(await json(response));
      if (response.status !== 201 || response.headers.get('Location') !== `${base}/${result.deliveryId}` || result.recipientId !== input.recipientId || result.scheduledDate !== input.scheduledDate || result.startTimeWindow === null || result.endTimeWindow === null || wholeTime(result.startTimeWindow) !== wholeTime(input.startTimeWindow) || wholeTime(result.endTimeWindow) !== wholeTime(input.endTimeWindow) || result.priorityLevel !== input.priorityLevel || result.specialInstructions !== normalized.specialInstructions || result.deliveryStatus !== 'PENDING' || result.operatorId !== null || result.operatorName !== null) throw new ApiError(502);
      return result;
    },
  };
}
export const deliveriesApi = createDeliveriesClient();
