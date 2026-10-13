import { describe, expect, it, vi } from 'vitest';
import { ApiError, createApiClient, parseAccount } from './api';
const admin = { id: 1, username: 'admin', role: 'ADMIN', operatorId: null, enabled: true, passwordChangeRequired: false };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
describe('session API contract', () => {
  it('loads and validates identity with same-origin no-store requests', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json(admin));
    expect(await createApiClient(fetcher).session(new AbortController().signal)).toEqual(admin);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0]?.[1]).toMatchObject({ credentials: 'same-origin', cache: 'no-store', redirect: 'error' });
  });
  it('adds a validated CSRF token to logout without storing it', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'test-token' })).mockResolvedValueOnce(new Response(null, { status: 204 }));
    await createApiClient(fetcher).logout();
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(new URL(String(fetcher.mock.calls[1]?.[0])).pathname).toBe('/api/auth/logout');
    const options = fetcher.mock.calls[1]?.[1];
    expect(options?.method).toBe('POST');
    expect(new Headers(options?.headers).get('X-CSRF-TOKEN')).toBe('test-token');
    expect(storage).not.toHaveBeenCalled();
  });
  it('never automatically retries writes and refreshes CSRF only on a later explicit request', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'old' }))
      .mockResolvedValueOnce(json({ error: 'private server detail' }, 403))
      .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'new' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = createApiClient(fetcher);
    await expect(client.logout()).rejects.toMatchObject({ status: 403 });
    expect(fetcher).toHaveBeenCalledTimes(2);
    await client.logout();
    expect(fetcher).toHaveBeenCalledTimes(4);
    expect(new Headers(fetcher.mock.calls[3]?.[1]?.headers).get('X-CSRF-TOKEN')).toBe('new');
  });
  it.each(['https://foreign.example/api/test', '/html/login.html', '/api/../html/login.html'])('rejects unauthorized target %s before sending anything', async path => {
    const fetcher = vi.fn<typeof fetch>();
    await expect(createApiClient(fetcher).request(path, { method: 'POST' })).rejects.toBeInstanceOf(ApiError);
    expect(fetcher).not.toHaveBeenCalled();
  });
  it('prevents callers from overriding credentials, cache and redirect policy', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json(admin));
    await createApiClient(fetcher).request('/api/auth/me', { credentials: 'include', cache: 'force-cache', redirect: 'follow' });
    expect(fetcher.mock.calls[0]?.[1]).toMatchObject({ credentials: 'same-origin', cache: 'no-store', redirect: 'error' });
  });
  it('distinguishes required password setup without exposing response text', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json({ passwordChangeRequired: true, error: 'private detail' }, 403));
    await expect(createApiClient(fetcher).session(new AbortController().signal)).rejects.toMatchObject({ status: 403, passwordChangeRequired: true, message: 'You do not have permission for this action.' });
  });
  it('keeps request cancellation recognizable', async () => {
    const fetcher = vi.fn<typeof fetch>().mockRejectedValue(new DOMException('Cancelled', 'AbortError'));
    await expect(createApiClient(fetcher).session(new AbortController().signal)).rejects.toMatchObject({ name: 'AbortError' });
  });
  it('uses fixed messages for malformed JSON and server failures', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValueOnce(new Response('private body')).mockResolvedValueOnce(new Response('private body', { status: 500 }));
    const client = createApiClient(fetcher);
    await expect(client.session(new AbortController().signal)).rejects.toMatchObject({ status: 502 });
    await expect(client.session(new AbortController().signal)).rejects.toMatchObject({ status: 500, message: 'The request could not be completed. Please try again.' });
  });
  it.each([null, [], { ...admin, id: '1' }, { ...admin, role: 'UNKNOWN' }, { ...admin, username: 123 }, { ...admin, passwordChangeRequired: undefined }, { ...admin, role: 'DRIVER', operatorId: null }])('rejects malformed identities', value => {
    expect(() => parseAccount(value)).toThrow(ApiError);
  });
  it('rejects disabled identities', () => { expect(() => parseAccount({ ...admin, enabled: false })).toThrow(ApiError); });
  it('rejects a poisoned CSRF header before logout', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json({ headerName: 'Authorization', token: 'test-token' }));
    await expect(createApiClient(fetcher).logout()).rejects.toMatchObject({ status: 502 });
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
