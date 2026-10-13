import { describe, it, expect, vi } from 'vitest';
import { ApiError } from './api';
import { createDeliveriesClient, inputError, parseDeliveryPage, parseDelivery, validDate } from './deliveries-api';
import type { DeliveryInput } from './deliveries-api';
const input: DeliveryInput = { recipientId: 1, scheduledDate: '2020-01-02', startTimeWindow: '09:00', endTimeWindow: '12:00', priorityLevel: 'MEDIUM', specialInstructions: '  Side entrance  ' };
const details = { deliveryId: 7, recipientId: 1, operatorId: null, clientName: 'Alex Smith', operatorName: null, scheduledDate: '2020-01-02', startTimeWindow: '09:00:00', endTimeWindow: '12:00:00', priorityLevel: 'MEDIUM', deliveryStatus: 'PENDING', specialInstructions: 'Side entrance' };
const summary = { ...details, firstName: 'Alex', lastName: 'Smith' };
const page = { deliveries: [summary], page: 0, size: 20, totalElements: 1, totalPages: 1 };
const signal = new AbortController().signal;
function response(body: unknown = details, location: string | null = '/api/admin/deliveries/7', status = 201) { return new Response(JSON.stringify(body), { status, headers: location ? { Location: location } : {} }); }
describe('delivery client contracts', () => {
  it('creates only writable fields and checks the returned ID and pending assignment', async () => {
    const request = vi.fn().mockResolvedValue(response());
    expect(await createDeliveriesClient(request).create({ ...input, ...{ operatorId: 99, deliveryStatus: 'COMPLETED' } }, signal)).toEqual(details);
    expect(request).toHaveBeenCalledWith('/api/admin/deliveries', expect.objectContaining({ method: 'POST', signal, body: JSON.stringify({ ...input, specialInstructions: 'Side entrance' }) }));
  });
  it('omits status for reconciliation and bounds page size', async () => {
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify(page)));
    await createDeliveriesClient(request).directory(0, { scheduledDate: '2020-01-02', recipientId: 1, status: '' }, signal);
    expect(request).toHaveBeenCalledWith('/api/admin/deliveries?page=0&size=20&scheduledDate=2020-01-02&recipientId=1', { signal });
  });
  it('sends explicit status filters', async () => { const request=vi.fn().mockResolvedValue(new Response(JSON.stringify(page))); await createDeliveriesClient(request).directory(0,{scheduledDate:'',recipientId:null,status:'COMPLETED'},signal); expect(request.mock.calls[0]?.[0]).toContain('status=COMPLETED'); });
  it.each([null, '/api/admin/deliveries/8', 'https://example.test/7'])('rejects an uncertain location %s', async location => { await expect(createDeliveriesClient(vi.fn().mockResolvedValue(response(details,location))).create(input,signal)).rejects.toMatchObject({status:502}); });
  it.each([{recipientId:2},{deliveryStatus:'IN_PROGRESS',operatorId:2},{specialInstructions:'Different'},{scheduledDate:'2020-01-03'},{startTimeWindow:'10:00:00'},{priorityLevel:'HIGH'}])('rejects a mismatched creation acknowledgement %j', async change => { await expect(createDeliveriesClient(vi.fn().mockResolvedValue(response({...details,...change}))).create(input,signal)).rejects.toMatchObject({status:502}); });
  it('never retries a write after a malformed acknowledgement or transport failure', async () => { const request=vi.fn().mockResolvedValue(new Response('invalid',{status:201})); await expect(createDeliveriesClient(request).create(input,signal)).rejects.toBeInstanceOf(ApiError); expect(request).toHaveBeenCalledTimes(1); request.mockRejectedValue(new ApiError(0)); await expect(createDeliveriesClient(request).create(input,signal)).rejects.toBeInstanceOf(ApiError); expect(request).toHaveBeenCalledTimes(2); });
  it('rejects local invalid inputs before sending', async () => { const request=vi.fn(); const client=createDeliveriesClient(request); await expect(client.create({...input,startTimeWindow:'13:00'},signal)).rejects.toMatchObject({status:400}); await expect(client.directory(10001,{scheduledDate:'',status:'',recipientId:null},signal)).rejects.toMatchObject({status:400}); await expect(client.directory(0,{scheduledDate:'2026-02-30',status:'',recipientId:null},signal)).rejects.toMatchObject({status:400}); expect(request).not.toHaveBeenCalled(); });
  it('validates calendar dates and same-day whole-second windows while allowing overdue dates', () => { expect(validDate('2020-01-02')).toBe(true); expect(validDate('2026-02-30')).toBe(false); expect(validDate('0999-01-01')).toBe(false); expect(inputError(input)).toBeNull(); expect(inputError({...input,endTimeWindow:'09:00'})).toMatch(/end after/); expect(inputError({...input,startTimeWindow:'09:00:00.5'})).toMatch(/whole seconds/); expect(inputError({...input,specialInstructions:'x'.repeat(256)})).toMatch(/255/); });
  it('rejects poisoned metadata, duplicate rows and wrong detail IDs', async () => { expect(()=>parseDeliveryPage({...page,deliveries:[summary,summary]},0)).toThrow(ApiError); expect(()=>parseDeliveryPage({...page,totalPages:2},0)).toThrow(ApiError); expect(()=>parseDelivery({...details,priorityLevel:0})).toThrow(ApiError); await expect(createDeliveriesClient(vi.fn().mockResolvedValue(new Response(JSON.stringify(details)))).read(8,signal)).rejects.toMatchObject({status:502}); });
  it('allows missing legacy scheduling fields and fractional legacy read times without accepting them for creation', () => { expect(parseDelivery({...details,scheduledDate:null,startTimeWindow:null,endTimeWindow:'23:59:59.999999999'}).scheduledDate).toBeNull(); });
});
