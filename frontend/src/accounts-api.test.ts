import { describe, expect, it, vi } from 'vitest';
import { ApiError } from './api';
import { createAccountsClient, parseCredential, parseSummary } from './accounts-api';
import type { createApiClient } from './api';
const driver = { id: 2, username: 'test.driver', role: 'DRIVER', operatorId: 4, enabled: true, passwordChangeRequired: true };
const credential = { account: driver, temporaryPassword: 'A'.repeat(32), expiresAt: '2026-10-14T12:00:00Z' };
const json = (v: unknown) => new Response(JSON.stringify(v));
type Request = ReturnType<typeof createApiClient>['request'];
describe('account API boundary', () => {
  it('accepts disabled driver summaries and disabled reset results', () => {
    expect(parseSummary({ ...driver, enabled: false }).enabled).toBe(false);
    expect(parseCredential({ ...credential, account: { ...driver, enabled: false } }).account.enabled).toBe(false);
  });
  it.each([null, { ...driver, id: '2' }, { ...driver, role: 'UNKNOWN' }, { ...driver, operatorId: null }])('rejects malformed summaries', v => { expect(() => parseSummary(v)).toThrow(ApiError); });
  it.each([{ ...credential, temporaryPassword: 'short' }, { ...credential, expiresAt: 'invalid' }, { ...credential, account: { ...driver, passwordChangeRequired: false } }])('rejects malformed credentials without exposing their contents', v => { expect(() => parseCredential(v)).toThrow(ApiError); });
  it('loads bounded pages and encodes the operator search', async () => {
    const request = vi.fn<Request>().mockResolvedValueOnce(json({ accounts: [driver], page: 0, size: 20, totalElements: 1, totalPages: 1 })).mockResolvedValueOnce(json({ operators: [{ operatorId: 4, name: '<img src=x>' }], page: 1, size: 50, totalElements: 51, totalPages: 2 }));
    const client = createAccountsClient(request); const signal = new AbortController().signal;
    expect((await client.accounts(0, signal)).items).toEqual([driver]); await client.operators(1, 'A&B', signal);
    expect(request.mock.calls[1]?.[0]).toBe('/api/admin/accounts/eligible-operators?page=1&size=50&search=A%26B'); expect(request.mock.calls[1]?.[1]?.signal).toBe(signal);
  });
  it('rejects mismatched pagination and duplicate IDs', async () => {
    const request = vi.fn<Request>().mockResolvedValueOnce(json({ accounts: [driver, driver], page: 0, size: 20, totalElements: 2, totalPages: 1 })).mockResolvedValueOnce(json({ accounts: [driver], page: 1, size: 20, totalElements: 1, totalPages: 1 }));
    const client = createAccountsClient(request); await expect(client.accounts(0, new AbortController().signal)).rejects.toMatchObject({ status: 502 }); await expect(client.accounts(0, new AbortController().signal)).rejects.toMatchObject({ status: 502 });
  });
  it('sends narrow write payloads and validates the credential belongs to the requested operator', async () => {
    const request = vi.fn<Request>().mockResolvedValueOnce(json(credential)); const signal = new AbortController().signal;
    expect(await createAccountsClient(request).create('test.driver', 4, 'test administrator password', signal)).toEqual(credential);
    expect(request).toHaveBeenCalledWith('/api/admin/accounts/drivers', { method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'test.driver', operatorId: 4, currentPassword: 'test administrator password' }) });
  });
  it('does not retry failed resets', async () => { const request = vi.fn<Request>().mockRejectedValue(new ApiError(409)); await expect(createAccountsClient(request).reset(2, 'test password', new AbortController().signal)).rejects.toMatchObject({ status: 409 }); expect(request).toHaveBeenCalledTimes(1); });
  it('rejects mismatched reset and enabled acknowledgements', async () => {
    const request = vi.fn<Request>().mockResolvedValueOnce(json({ ...credential, account: { ...driver, id: 3 } })).mockResolvedValueOnce(json(driver)); const client = createAccountsClient(request);
    await expect(client.reset(2, 'test password', new AbortController().signal)).rejects.toMatchObject({ status: 502 }); await expect(client.enabled(2, false, 'test password', new AbortController().signal)).rejects.toMatchObject({ status: 502 });
  });
  it('rejects invalid IDs and oversized search before sending a request', async () => { const request = vi.fn<Request>(); const client = createAccountsClient(request); await expect(client.reset(-1, 'test', new AbortController().signal)).rejects.toMatchObject({ status: 400 }); await expect(client.operators(0, 'x'.repeat(101), new AbortController().signal)).rejects.toMatchObject({ status: 400 }); expect(request).not.toHaveBeenCalled(); });
});
