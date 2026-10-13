import { api, ApiError } from './api';
export interface RecipientInput { firstName: string; lastName: string; address: string; contactNumber: string | null; priorityLevel: 'HIGH' | 'MEDIUM' | 'LOW' }
export interface Recipient extends RecipientInput { recipientId: number }
export interface RecipientVersion { recipient: Recipient; etag: string }
export interface RecipientPage { recipients: Recipient[]; page: number; size: number; totalElements: number; totalPages: number }
export interface RecipientsClient {
  directory(page: number, search: string, signal: AbortSignal): Promise<RecipientPage>;
  read(id: number, signal: AbortSignal): Promise<RecipientVersion>;
  create(input: RecipientInput, signal: AbortSignal): Promise<RecipientVersion>;
  update(id: number, etag: string, input: RecipientInput, signal: AbortSignal): Promise<RecipientVersion>;
}
const natural = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 0;
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v);
function id(v: number) { if (!natural(v) || v < 1) throw new ApiError(400); return v; }
function tag(v: string | null) { if (!v || !/^"[a-f0-9]{64}"$/.test(v)) throw new ApiError(502); return v; }
export function parseRecipient(v: unknown): Recipient {
  if (!object(v) || !natural(v['recipientId']) || v['recipientId'] < 1
    || typeof v['firstName'] !== 'string' || typeof v['lastName'] !== 'string' || typeof v['address'] !== 'string'
    || !(v['contactNumber'] === null || typeof v['contactNumber'] === 'string')
    || !(['HIGH', 'MEDIUM', 'LOW'] as unknown[]).includes(v['priorityLevel'])) throw new ApiError(502);
  return { recipientId: v['recipientId'], firstName: v['firstName'], lastName: v['lastName'], address: v['address'], contactNumber: v['contactNumber'], priorityLevel: v['priorityLevel'] as RecipientInput['priorityLevel'] };
}
export function parseRecipientPage(v: unknown, page: number): RecipientPage {
  if (!object(v) || v['page'] !== page || v['size'] !== 20 || !natural(v['totalElements']) || !natural(v['totalPages'])
    || v['totalPages'] !== Math.ceil(v['totalElements'] / 20) || !Array.isArray(v['recipients']) || v['recipients'].length > 20) throw new ApiError(502);
  const recipients = v['recipients'].map(parseRecipient);
  if (new Set(recipients.map(r => r.recipientId)).size !== recipients.length) throw new ApiError(502);
  return { recipients, page, size: 20, totalElements: v['totalElements'], totalPages: v['totalPages'] };
}
export function createRecipientsClient(request = api.request): RecipientsClient {
  const base = '/api/admin/recipients';
  async function json(response: Response): Promise<unknown> { try { return await response.json(); } catch { throw new ApiError(502); } }
  async function version(response: Response, expected?: number) {
    const recipient = parseRecipient(await json(response));
    if (expected !== undefined && recipient.recipientId !== expected) throw new ApiError(502);
    return { recipient, etag: tag(response.headers.get('ETag')) };
  }
  function body(input: RecipientInput) { return JSON.stringify({ firstName: input.firstName.trim(), lastName: input.lastName.trim(), address: input.address.trim(), contactNumber: input.contactNumber?.trim() || null, priorityLevel: input.priorityLevel }); }
  return {
    async directory(page, search, signal) {
      if (!natural(page) || page > 10000 || search.length > 100) throw new ApiError(400);
      return parseRecipientPage(await json(await request(`${base}?${new URLSearchParams({ page: String(page), size: '20', search })}`, { signal })), page);
    },
    async read(value, signal) { return version(await request(`${base}/${id(value)}`, { signal }), value); },
    async create(input, signal) { return version(await request(base, { method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: body(input) })); },
    async update(value, etag, input, signal) { tag(etag); return version(await request(`${base}/${id(value)}`, { method: 'PUT', signal, headers: { 'Content-Type': 'application/json', 'If-Match': etag }, body: body(input) }), value); },
  };
}
export const recipientsApi = createRecipientsClient();
