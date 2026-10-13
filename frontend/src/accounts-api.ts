import { api, ApiError } from './api';
import type { Account } from './api';
export type AccountSummary = Account;
export interface EligibleOperator { operatorId: number; name: string }
export interface Page<T> { items: T[]; page: number; size: number; totalElements: number; totalPages: number }
export interface Credential { account: AccountSummary; temporaryPassword: string; expiresAt: string }
export interface AccountsClient {
  accounts(page: number, signal: AbortSignal): Promise<Page<AccountSummary>>;
  operators(page: number, search: string, signal: AbortSignal): Promise<Page<EligibleOperator>>;
  create(username: string, operatorId: number, password: string, signal: AbortSignal): Promise<Credential>;
  enabled(id: number, enabled: boolean, password: string, signal: AbortSignal): Promise<void>;
  reset(id: number, password: string, signal: AbortSignal): Promise<Credential>;
}
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const positive = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) > 0;
const nonnegative = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 0;
export function parseSummary(v: unknown): AccountSummary {
  if (!object(v) || !positive(v['id']) || typeof v['username'] !== 'string' || v['username'].length < 1 || v['username'].length > 64
    || (v['role'] !== 'ADMIN' && v['role'] !== 'DRIVER') || typeof v['enabled'] !== 'boolean' || typeof v['passwordChangeRequired'] !== 'boolean'
    || (v['role'] === 'ADMIN' ? v['operatorId'] !== null : !positive(v['operatorId']))) throw new ApiError(502);
  return v as unknown as AccountSummary;
}
function page<T>(v: unknown, key: string, expected: number, size: number, parse: (item: unknown) => T): Page<T> {
  if (!object(v) || v['page'] !== expected || v['size'] !== size || !nonnegative(v['totalElements']) || !nonnegative(v['totalPages'])
    || v['totalPages'] !== Math.ceil(v['totalElements'] / size) || !Array.isArray(v[key]) || v[key].length > size) throw new ApiError(502);
  return { items: v[key].map(parse), page: expected, size, totalElements: v['totalElements'], totalPages: v['totalPages'] };
}
export function parseCredential(v: unknown): Credential {
  if (!object(v) || typeof v['temporaryPassword'] !== 'string' || !/^[A-Za-z0-9_-]{32}$/.test(v['temporaryPassword'])
    || typeof v['expiresAt'] !== 'string' || !Number.isFinite(Date.parse(v['expiresAt']))) throw new ApiError(502);
  const account = parseSummary(v['account']);
  if (account.role !== 'DRIVER' || !account.passwordChangeRequired) throw new ApiError(502);
  return { account, temporaryPassword: v['temporaryPassword'], expiresAt: v['expiresAt'] };
}
export function createAccountsClient(request = api.request): AccountsClient {
  async function json(path: string, signal: AbortSignal, method = 'GET', body?: object): Promise<unknown> {
    const response = await request(path, { method, signal, ...(body ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}) });
    try { return await response.json(); } catch { throw new ApiError(502); }
  }
  function validId(id: number) { if (!positive(id)) throw new ApiError(400); return id; }
  function validPage(value: number) { if (!nonnegative(value)) throw new ApiError(400); return value; }
  return {
    async accounts(number, signal) {
      const result = page(await json(`/api/admin/accounts?page=${validPage(number)}&size=20`, signal), 'accounts', number, 20, parseSummary);
      if (new Set(result.items.map(a => a.id)).size !== result.items.length) throw new ApiError(502);
      return result;
    },
    async operators(number, search, signal) {
      if (search.length > 100) throw new ApiError(400);
      const query = new URLSearchParams({ page: String(validPage(number)), size: '50', search });
      const result = page(await json(`/api/admin/accounts/eligible-operators?${query}`, signal), 'operators', number, 50, v => {
        if (!object(v) || !positive(v['operatorId']) || typeof v['name'] !== 'string') throw new ApiError(502);
        return { operatorId: v['operatorId'], name: v['name'] };
      });
      if (new Set(result.items.map(o => o.operatorId)).size !== result.items.length) throw new ApiError(502);
      return result;
    },
    async create(username, operatorId, currentPassword, signal) {
      const result = parseCredential(await json('/api/admin/accounts/drivers', signal, 'POST', { username, operatorId: validId(operatorId), currentPassword }));
      if (result.account.username !== username.trim().toLowerCase() || result.account.operatorId !== operatorId || !result.account.enabled) throw new ApiError(502);
      return result;
    },
    async enabled(id, enabled, currentPassword, signal) {
      const result = parseSummary(await json(`/api/admin/accounts/${validId(id)}/enabled`, signal, 'PATCH', { enabled, currentPassword }));
      if (result.id !== id || result.role !== 'DRIVER' || result.enabled !== enabled) throw new ApiError(502);
    },
    async reset(id, currentPassword, signal) {
      const result = parseCredential(await json(`/api/admin/accounts/${validId(id)}/reset-password`, signal, 'POST', { currentPassword }));
      if (result.account.id !== id) throw new ApiError(502);
      return result;
    },
  };
}
export const accountsApi = createAccountsClient();
