export type Role = 'ADMIN' | 'DRIVER';
export interface Account { id: number; username: string; role: Role; operatorId: number | null; enabled: boolean; passwordChangeRequired: boolean }
interface Csrf { headerName: string; token: string }
export class ApiError extends Error {
  constructor(readonly status: number, readonly passwordChangeRequired = false) {
    super(status === 401 ? 'Your session has ended. Sign in again.' : status === 403 ? 'You do not have permission for this action.' : 'The request could not be completed. Please try again.');
    this.name = 'ApiError';
  }
}
function record(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value); }
export function parseAccount(value: unknown): Account {
  if (!record(value) || !Number.isSafeInteger(value['id']) || Number(value['id']) < 1 || typeof value['username'] !== 'string'
    || value['username'].length < 1 || value['username'].length > 64 || (value['role'] !== 'ADMIN' && value['role'] !== 'DRIVER')
    || typeof value['enabled'] !== 'boolean' || typeof value['passwordChangeRequired'] !== 'boolean'
    || (value['operatorId'] !== null && (!Number.isSafeInteger(value['operatorId']) || Number(value['operatorId']) < 1))
    || (value['role'] === 'DRIVER' && value['operatorId'] === null) || (value['role'] === 'ADMIN' && value['operatorId'] !== null)) throw new ApiError(502);
  if (!value['enabled']) throw new ApiError(401);
  return { id: Number(value['id']), username: value['username'], role: value['role'] as Role,
    operatorId: value['operatorId'] as number | null, enabled: value['enabled'], passwordChangeRequired: value['passwordChangeRequired'] };
}
export interface SessionClient { session(signal: AbortSignal): Promise<Account>; logout(): Promise<void> }
export function createApiClient(fetcher: typeof fetch = fetch) {
  let csrf: Csrf | undefined;
  async function send(url: URL, options: RequestInit): Promise<Response> {
    try { return await fetcher(url, { ...options, credentials: 'same-origin', cache: 'no-store', redirect: 'error' }); }
    catch (error) { if (error instanceof DOMException && error.name === 'AbortError') throw error; throw new ApiError(0); }
  }
  function target(path: string) {
    const url = new URL(path, window.location.origin);
    if (url.origin !== window.location.origin || !url.pathname.startsWith('/api/') || url.username || url.password) throw new ApiError(400);
    return url;
  }
  async function checked(response: Response) {
    if (response.ok) return response;
    if (response.status === 401 || response.status === 403) csrf = undefined;
    let setupRequired = false;
    if (response.status === 403) {
      try { const value: unknown = await response.json(); setupRequired = record(value) && value['passwordChangeRequired'] === true; } catch { /* Never expose raw server responses. */ }
    }
    throw new ApiError(response.status, setupRequired);
  }
  async function json(response: Response): Promise<unknown> {
    try { return await response.json(); } catch { throw new ApiError(502); }
  }
  async function request(path: string, options: RequestInit = {}) {
    const url = target(path);
    const method = (options.method ?? 'GET').toUpperCase();
    const headers = new Headers(options.headers);
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      if (!csrf) {
        const value = await json(await checked(await send(target('/api/auth/csrf'), { method: 'GET' })));
        if (!record(value) || value['headerName'] !== 'X-CSRF-TOKEN' || typeof value['token'] !== 'string' || value['token'].length < 1 || value['token'].length > 4096) throw new ApiError(502);
        csrf = { headerName: value['headerName'], token: value['token'] };
      }
      headers.set(csrf.headerName, csrf.token);
    }
    return checked(await send(url, { ...options, method, headers }));
  }
  return { request,
    async session(signal: AbortSignal) { return parseAccount(await json(await request('/api/auth/me', { signal }))); },
    async logout() { await request('/api/auth/logout', { method: 'POST' }); csrf = undefined; },
  };
}
export const api = createApiClient();
