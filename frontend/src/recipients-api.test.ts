import { describe, it, expect, vi } from 'vitest';
import { createRecipientsClient, parseRecipientPage } from './recipients-api';
import { ApiError } from './api';
const recipient = { recipientId: 1, firstName: 'Alex', lastName: 'Smith', address: 'Example', contactNumber: null, priorityLevel: 'MEDIUM' as const };
const etag = '"' + 'a'.repeat(64) + '"';
const signal = new AbortController().signal;
function response(value: unknown = recipient, token: string | null = etag) { return new Response(JSON.stringify(value), { headers: token ? { ETag: token } : {} }); }
describe('recipient API', () => {
  it('encodes bounded search without changing paths and propagates cancellation', async () => {
    const request = vi.fn().mockResolvedValue(response({ recipients: [], page: 2, size: 20, totalElements: 0, totalPages: 0 }));
    await createRecipientsClient(request).directory(2, 'a&search=%_', signal);
    expect(request).toHaveBeenCalledWith('/api/admin/recipients?page=2&size=20&search=a%26search%3D%25_', { signal });
  });
  it('rejects invalid page/search before sending', async () => { const request = vi.fn(); const client = createRecipientsClient(request); await expect(client.directory(-1,'',signal)).rejects.toBeInstanceOf(ApiError); await expect(client.directory(0,'x'.repeat(101),signal)).rejects.toBeInstanceOf(ApiError); expect(request).not.toHaveBeenCalled(); });
  it('sends exact strong version and only normalized writable fields', async () => {
    const request = vi.fn().mockResolvedValue(response()); const client = createRecipientsClient(request);
    await client.update(1, etag, { ...recipient, firstName: ' Alex ', contactNumber: ' ' }, signal);
    expect(request).toHaveBeenCalledWith('/api/admin/recipients/1', expect.objectContaining({ method: 'PUT', signal, headers: { 'Content-Type': 'application/json', 'If-Match': etag }, body: JSON.stringify({ firstName: 'Alex', lastName: 'Smith', address: 'Example', contactNumber: null, priorityLevel: 'MEDIUM' }) }));
  });
  it.each([null, 'W/'+etag, '*'])('rejects missing or malformed response version %s', async token => { await expect(createRecipientsClient(vi.fn().mockResolvedValue(response(recipient,token))).create(recipient,signal)).rejects.toBeInstanceOf(ApiError); });
  it('rejects wrong recipient ID and malformed acknowledgement', async () => { await expect(createRecipientsClient(vi.fn().mockResolvedValue(response())).read(2,signal)).rejects.toBeInstanceOf(ApiError); await expect(createRecipientsClient(vi.fn().mockResolvedValue(new Response('invalid'))).create(recipient,signal)).rejects.toBeInstanceOf(ApiError); });
  it('does not retry uncertain writes', async () => { const request = vi.fn().mockRejectedValue(new ApiError(0)); await expect(createRecipientsClient(request).create(recipient,signal)).rejects.toBeInstanceOf(ApiError); expect(request).toHaveBeenCalledTimes(1); });
  it('rejects duplicate IDs and malformed pagination/priority', () => { const page = { recipients: [recipient,recipient], page: 0, size: 20, totalElements: 2, totalPages: 1 }; expect(()=>parseRecipientPage(page,0)).toThrow(ApiError); expect(()=>parseRecipientPage({...page,recipients:[{...recipient,priorityLevel:0}]},0)).toThrow(ApiError); expect(()=>parseRecipientPage({...page,totalPages:3},0)).toThrow(ApiError); });
});
