import { describe, expect, it, vi } from 'vitest';
import { ApiError } from './api';
import { createWarehousesClient, coordinate } from './warehouses-api';
const warehouse = { warehouseId: 3, name: 'Depot', address: 'Address', latitude: 53.123456, longitude: -2.5 };
const etag = `"${'a'.repeat(64)}"`; const signal = new AbortController().signal;
function response(value: unknown = warehouse, status = 200, tag: string | null = etag) { return new Response(JSON.stringify(value), { status, headers: tag ? { ETag: tag } : {} }); }
describe('warehouse API boundary', () => {
  it('sends scalar writes, conditional edits and conditional deletion without replay', async () => {
    const request = vi.fn().mockResolvedValueOnce(response(warehouse, 201)).mockResolvedValueOnce(response()).mockResolvedValueOnce(new Response(null, { status: 204 })); const client = createWarehousesClient(request);
    await client.create({ ...warehouse, name: ' Depot ' }, signal); await client.update(3, etag, warehouse, signal); await client.remove(3, etag, signal);
    expect(JSON.parse(request.mock.calls[0]?.[1].body as string)).toEqual({ name: 'Depot', address: 'Address', latitude: 53.123456, longitude: -2.5 });
    expect(request.mock.calls[1]?.[1].headers['If-Match']).toBe(etag); expect(request.mock.calls[2]?.[1].method).toBe('DELETE'); expect(request).toHaveBeenCalledTimes(3);
  });
  it('encodes literal search and validates bounded directory rows', async () => {
    const request = vi.fn().mockResolvedValue(response({ warehouses: [warehouse], page: 0, size: 20, totalElements: 1, totalPages: 1 }));
    expect((await createWarehousesClient(request).directory(0, '_%! &', signal)).warehouses).toEqual([warehouse]);
    expect(new URL(request.mock.calls[0]?.[0] as string, 'http://localhost').searchParams.get('search')).toBe('_%! &');
  });
  it.each([null, '*', 'W/'+etag, '"bad"'])('rejects malformed acknowledgement ETag %s', async tag => { const request = vi.fn().mockResolvedValue(response(warehouse, 201, tag)); await expect(createWarehousesClient(request).create(warehouse, signal)).rejects.toMatchObject({ status: 502 }); expect(request).toHaveBeenCalledTimes(1); });
  it.each([{ ...warehouse, warehouseId: 0 }, { ...warehouse, latitude: '53' }, { ...warehouse, latitude: null }])('rejects malformed warehouse details %j', async value => { await expect(createWarehousesClient(vi.fn().mockResolvedValue(response(value))).read(3, signal)).rejects.toBeInstanceOf(ApiError); });
  it('rejects a wrong target ID and wrong write status', async () => { await expect(createWarehousesClient(vi.fn().mockResolvedValue(response({ ...warehouse, warehouseId: 4 }))).read(3, signal)).rejects.toMatchObject({ status: 502 }); await expect(createWarehousesClient(vi.fn().mockResolvedValue(response())).create(warehouse, signal)).rejects.toMatchObject({ status: 502 }); });
  it('rejects oversized/duplicate directories and malformed JSON', async () => {
    for (const rows of [[warehouse, warehouse], Array.from({ length: 21 }, (_, i) => ({ ...warehouse, warehouseId: i+1 }))]) await expect(createWarehousesClient(vi.fn().mockResolvedValue(response({ warehouses: rows, page: 0, size: 20, totalElements: rows.length, totalPages: Math.ceil(rows.length/20) }))).directory(0, '', signal)).rejects.toMatchObject({ status: 502 });
    await expect(createWarehousesClient(vi.fn().mockResolvedValue(new Response('invalid', { status: 201 }))).create(warehouse, signal)).rejects.toMatchObject({ status: 502 });
  });
  it('rejects invalid local input before sending a request', async () => {
    const request = vi.fn(); const client = createWarehousesClient(request);
    await expect(client.create({ ...warehouse, latitude: NaN }, signal)).rejects.toMatchObject({ status: 400 }); await expect(client.directory(10001, '', signal)).rejects.toMatchObject({ status: 400 }); await expect(client.read(2147483648, signal)).rejects.toMatchObject({ status: 400 }); expect(request).not.toHaveBeenCalled();
    expect(coordinate(0, 90)).toBe(true); expect(coordinate(-90, 90)).toBe(true); expect(coordinate(180, 180)).toBe(true);
  });
  it('keeps legacy invalid coordinates readable for correction but rejects mismatched write acknowledgements', async () => {
    const legacy = { ...warehouse, latitude: 91 }; expect((await createWarehousesClient(vi.fn().mockResolvedValue(response(legacy))).read(3, signal)).warehouse.latitude).toBe(91);
    await expect(createWarehousesClient(vi.fn().mockResolvedValue(response({ ...warehouse, name: 'Other warehouse' }, 201))).create(warehouse, signal)).rejects.toMatchObject({ status: 502 });
  });

});
